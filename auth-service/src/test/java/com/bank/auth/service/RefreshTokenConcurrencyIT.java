package com.bank.auth.service;

import com.bank.auth.dto.RefreshTokenRequest;
import com.bank.auth.entity.RefreshToken;
import com.bank.auth.entity.User;
import com.bank.auth.exception.InvalidRefreshTokenException;
import com.bank.auth.repository.RefreshTokenRepository;
import com.bank.auth.repository.UserRepository;
import com.bank.common.crypto.EncryptionKeyring;
import com.bank.common.security.JwtProperties;
import com.bank.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nhiều request refresh song song cùng một refresh token: nhờ SELECT ... FOR UPDATE chỉ một request được cấp token
 * mới, các request còn lại bị coi là dùng lại token đã thu hồi và toàn bộ token của user bị thu hồi.
 * Chạy: {@code mvn -pl auth-service -am verify -Pintegration-test} (cần Docker).
 */
@Testcontainers
@DataJpaTest(properties = "spring.config.location=classpath:/application-it.yml")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AuthService.class, UserMapperService.class, RefreshTokenConcurrencyIT.TestBeans.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefreshTokenConcurrencyIT {

    @Container
    @ServiceConnection
    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withUsername("bank_auth")
            .withPassword("Test_Auth1");

    @Autowired
    private AuthService authService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @MockitoBean
    private JwtTokenProvider tokenProvider;

    @TestConfiguration
    static class TestBeans {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }

        @Bean
        JwtProperties jwtProperties() {
            return new JwtProperties();
        }

        @Bean
        EncryptionKeyring encryptionKeyring() {
            return new EncryptionKeyring(Base64.getEncoder().encodeToString(new byte[32]));
        }
    }

    @BeforeAll
    static void createSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(ORACLE.getJdbcUrl(), ORACLE.getUsername(),
                ORACLE.getPassword())) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("../database/oracle/01_auth_schema.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("../database/oracle/07_auth_audit_operations.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("../database/oracle/14_auth_email_encryption.sql"));
        }
    }

    @Test
    void concurrentRefreshWithSameTokenShouldSucceedOnlyOnce() throws Exception {
        User user = new User();
        user.setUsername("concurrent-user");
        user.setEmail("concurrent@bank.local");
        user.setPasswordHash(passwordEncoder.encode("Secret@123"));
        userRepository.save(user);
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(sha256("shared-refresh-token"));
        token.setExpiresAt(LocalDateTime.now().plusDays(1));
        refreshTokenRepository.save(token);

        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add(() -> {
                try {
                    authService.refresh(new RefreshTokenRequest("shared-refresh-token"));
                    return true;
                } catch (InvalidRefreshTokenException e) {
                    return false;
                }
            });
        }
        int succeeded = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
            for (Future<Boolean> result : executor.invokeAll(calls)) {
                try {
                    if (result.get()) {
                        succeeded++;
                    }
                } catch (ExecutionException e) {
                    throw new AssertionError("refresh lỗi ngoài dự kiến", e.getCause());
                }
            }
        }

        assertThat(succeeded).isEqualTo(1);
        // Có request dùng lại token đã thu hồi → mọi refresh token của user (kể cả token vừa cấp) bị thu hồi
        assertThat(refreshTokenRepository.findAll()).allMatch(RefreshToken::getRevoked);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
