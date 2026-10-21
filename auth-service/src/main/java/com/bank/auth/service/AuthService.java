package com.bank.auth.service;

import com.bank.auth.dto.LoginRequest;
import com.bank.auth.dto.RefreshTokenRequest;
import com.bank.auth.dto.RegisterRequest;
import com.bank.auth.dto.TokenResponse;
import com.bank.auth.dto.UserResponse;
import com.bank.auth.entity.RefreshToken;
import com.bank.auth.entity.Role;
import com.bank.auth.entity.User;
import com.bank.auth.enums.UserStatusEnum;
import com.bank.auth.exception.AccountLockedException;
import com.bank.auth.exception.InvalidCredentialsException;
import com.bank.auth.exception.InvalidRefreshTokenException;
import com.bank.auth.exception.UserAlreadyExistsException;
import com.bank.auth.repository.RefreshTokenRepository;
import com.bank.auth.repository.RoleRepository;
import com.bank.auth.repository.UserRepository;
import com.bank.common.crypto.BlindIndexer;
import com.bank.common.crypto.EncryptionKeyring;
import com.bank.common.security.AuthUser;
import com.bank.common.security.JwtProperties;
import com.bank.common.security.JwtTokenProvider;
import com.bank.common.security.RoleCodes;
import com.bank.common.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

@Slf4j
@Service
public class AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final JwtProperties jwtProperties;
    private final UserMapperService userMapper;
    private final BlindIndexer blindIndexer;
    private final int maxFailedAttempts;
    private final Duration lockDuration;
    /** So khớp với hash giả khi user không tồn tại để thời gian phản hồi không lộ thông tin. */
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository, RoleRepository roleRepository,
                       RefreshTokenRepository refreshTokenRepository, PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider, JwtProperties jwtProperties, UserMapperService userMapper,
                       EncryptionKeyring keyring,
                       @Value("${bank.auth.max-failed-attempts:5}") int maxFailedAttempts,
                       @Value("${bank.auth.lock-duration:15m}") Duration lockDuration) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.jwtProperties = jwtProperties;
        this.userMapper = userMapper;
        this.blindIndexer = keyring.blindIndexer();
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockDuration = lockDuration;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new UserAlreadyExistsException("Tên đăng nhập đã tồn tại");
        }
        String emailHash = blindIndexer.hashEmail(request.email());
        if (userRepository.existsByEmailHash(emailHash)) {
            throw new UserAlreadyExistsException("Email đã được sử dụng");
        }
        Role customerRole = roleRepository.findByCode(RoleCodes.CUSTOMER)
                .orElseThrow(() -> new IllegalStateException("Thiếu role CUSTOMER trong BANK_AUTH.ROLES"));

        User user = new User();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setEmail(request.email());
        user.setEmailHash(emailHash);
        user.getRoles().add(customerRole);
        userRepository.save(user);
        log.info("Đăng ký người dùng mới #{}", user.getId());
        return userMapper.toResponse(user);
    }

    /** Không rollback khi sai mật khẩu để lưu được số lần đăng nhập sai. */
    @Transactional(noRollbackFor = {InvalidCredentialsException.class, AccountLockedException.class})
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByUsernameForUpdate(request.username()).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyPasswordHash);
            throw new InvalidCredentialsException();
        }
        LocalDateTime now = LocalDateTime.now();
        if (user.getStatus() != UserStatusEnum.ACTIVE) {
            throw new AccountLockedException("Tài khoản đã bị khóa hoặc vô hiệu hóa");
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            throw new AccountLockedException("Tài khoản tạm khóa do đăng nhập sai nhiều lần, vui lòng thử lại sau");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            int failed = user.getFailedLoginCount() + 1;
            if (failed >= maxFailedAttempts) {
                user.setLockedUntil(now.plus(lockDuration));
                failed = 0;
                log.warn("Tạm khóa người dùng #{} do đăng nhập sai {} lần", user.getId(), maxFailedAttempts);
            }
            user.setFailedLoginCount(failed);
            throw new InvalidCredentialsException();
        }

        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        return issueTokens(user);
    }

    /**
     * Xoay vòng refresh token: token cũ bị thu hồi, phát hành token mới. Nếu một token đã thu hồi
     * bị dùng lại (dấu hiệu bị đánh cắp) → thu hồi toàn bộ token của người dùng.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public TokenResponse refresh(RefreshTokenRequest request) {
        RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(sha256(request.refreshToken()))
                .orElseThrow(InvalidRefreshTokenException::new);
        Long userId = token.getUser().getId();
        if (Boolean.TRUE.equals(token.getRevoked())) {
            refreshTokenRepository.revokeAllByUserId(userId);
            log.warn("Refresh token đã thu hồi bị dùng lại, thu hồi toàn bộ token của người dùng #{}", userId);
            throw new InvalidRefreshTokenException();
        }
        if (token.isExpired(LocalDateTime.now())) {
            throw new InvalidRefreshTokenException();
        }
        User user = userRepository.findWithRolesById(userId).orElseThrow(InvalidRefreshTokenException::new);
        if (user.getStatus() != UserStatusEnum.ACTIVE) {
            throw new AccountLockedException("Tài khoản đã bị khóa hoặc vô hiệu hóa");
        }
        token.setRevoked(true);
        return issueTokens(user);
    }

    @Transactional
    public void logout(RefreshTokenRequest request) {
        AuthUser currentUser = SecurityUtils.requireCurrentUser();
        refreshTokenRepository.findByTokenHash(sha256(request.refreshToken()))
                .filter(token -> Objects.equals(token.getUser().getId(), currentUser.userId()))
                .ifPresent(token -> token.setRevoked(true));
    }

    private TokenResponse issueTokens(User user) {
        String accessToken = tokenProvider.generateAccessToken(userMapper.toAuthUser(user));
        String rawRefreshToken = generateRefreshToken();

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(sha256(rawRefreshToken));
        refreshToken.setExpiresAt(LocalDateTime.now().plus(jwtProperties.getRefreshTokenTtl()));
        refreshTokenRepository.save(refreshToken);

        return new TokenResponse(accessToken, rawRefreshToken, "Bearer",
                tokenProvider.getAccessTokenTtlSeconds(), userMapper.toResponse(user));
    }

    private static String generateRefreshToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM không hỗ trợ SHA-256", e);
        }
    }
}
