package com.bank.auth.service;

import com.bank.common.security.JwtTokenProvider;
import com.bank.common.security.TokenRevocationChecker;
import com.bank.common.util.CacheEvictionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Thu hồi ngay mọi access token đã cấp cho một người dùng (khóa user, đổi role): ghi mốc thời gian vào Redis,
 * JwtAuthenticationFilter ở gateway và các service từ chối token có iat trước mốc này.
 * Key sống bằng thời gian sống của access token (sau đó mọi token cũ đã hết hạn tự nhiên).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessTokenRevocationService {

    private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);

    private final StringRedisTemplate redisTemplate;
    private final JwtTokenProvider tokenProvider;

    /** Ghi sau khi transaction commit: thay đổi bị rollback thì token cũ vẫn hợp lệ. */
    public void revokeAllAfterCommit(Long userId) {
        CacheEvictionUtils.runAfterCommit(() -> revokeAll(userId));
    }

    public void revokeAll(Long userId) {
        try {
            redisTemplate.opsForValue().set(TokenRevocationChecker.revokedBeforeKey(userId),
                    String.valueOf(Instant.now().getEpochSecond()),
                    Duration.ofSeconds(tokenProvider.getAccessTokenTtlSeconds()).plus(CLOCK_SKEW));
            log.info("Thu hồi access token của người dùng #{}", userId);
        } catch (RuntimeException e) {
            // Refresh token đã bị thu hồi trong DB; access token cũ tối đa còn hiệu lực tới khi hết hạn
            log.error("Không ghi được mốc thu hồi token người dùng #{} vào Redis: {}", userId, e.getMessage());
        }
    }
}
