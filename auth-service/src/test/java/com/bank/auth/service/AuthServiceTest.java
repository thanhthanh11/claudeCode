package com.bank.auth.service;

import com.bank.auth.dto.LoginRequest;
import com.bank.auth.dto.RefreshTokenRequest;
import com.bank.auth.entity.RefreshToken;
import com.bank.auth.entity.User;
import com.bank.auth.exception.AccountLockedException;
import com.bank.auth.exception.InvalidCredentialsException;
import com.bank.auth.exception.InvalidRefreshTokenException;
import com.bank.auth.repository.RefreshTokenRepository;
import com.bank.auth.repository.RoleRepository;
import com.bank.auth.repository.UserRepository;
import com.bank.common.crypto.EncryptionKeyring;
import com.bank.common.security.JwtProperties;
import com.bank.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String PASSWORD = "Secret@123";

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private JwtTokenProvider tokenProvider;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private AuthService authService;
    private User user;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setRefreshTokenTtl(Duration.ofDays(7));
        authService = new AuthService(userRepository, roleRepository, refreshTokenRepository, passwordEncoder,
                tokenProvider, jwtProperties, new UserMapperService(),
                new EncryptionKeyring(Base64.getEncoder().encodeToString(new byte[32])), 5, Duration.ofMinutes(15));
        user = new User();
        user.setId(7L);
        user.setUsername("alice");
        user.setEmail("alice@bank.local");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        lenient().when(tokenProvider.generateAccessToken(any())).thenReturn("access-token");
    }

    @Test
    void loginShouldLockRowAndLockUserAfterMaxFailedAttempts() {
        when(userRepository.findByUsernameForUpdate("alice")).thenReturn(Optional.of(user));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("alice", "wrong")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        assertThat(user.getLockedUntil()).isAfter(LocalDateTime.now());
        assertThatThrownBy(() -> authService.login(new LoginRequest("alice", PASSWORD)))
                .isInstanceOf(AccountLockedException.class);
        // Phải đọc user bằng SELECT ... FOR UPDATE để các lần nhập sai song song không mất cập nhật bộ đếm
        verify(userRepository, never()).findByUsername(anyString());
    }

    @Test
    void successfulLoginShouldResetFailedCounter() {
        user.setFailedLoginCount(3);
        when(userRepository.findByUsernameForUpdate("alice")).thenReturn(Optional.of(user));

        assertThat(authService.login(new LoginRequest("alice", PASSWORD)).accessToken()).isEqualTo("access-token");
        assertThat(user.getFailedLoginCount()).isZero();
    }

    @Test
    void refreshShouldRotateTokenUnderRowLock() {
        RefreshToken token = refreshToken(false);
        when(refreshTokenRepository.findByTokenHashForUpdate(sha256("raw-token"))).thenReturn(Optional.of(token));
        when(userRepository.findWithRolesById(7L)).thenReturn(Optional.of(user));

        assertThat(authService.refresh(new RefreshTokenRequest("raw-token")).refreshToken()).isNotBlank();
        assertThat(token.getRevoked()).isTrue();
        verify(refreshTokenRepository, never()).findByTokenHash(anyString());
    }

    @Test
    void reusedRevokedRefreshTokenShouldRevokeAllTokensOfUser() {
        when(refreshTokenRepository.findByTokenHashForUpdate(sha256("raw-token")))
                .thenReturn(Optional.of(refreshToken(true)));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("raw-token")))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokenRepository).revokeAllByUserId(7L);
    }

    private RefreshToken refreshToken(boolean revoked) {
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(sha256("raw-token"));
        token.setExpiresAt(LocalDateTime.now().plusDays(1));
        token.setRevoked(revoked);
        return token;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
