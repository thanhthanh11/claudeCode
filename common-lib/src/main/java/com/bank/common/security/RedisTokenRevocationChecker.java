package com.bank.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Đọc mốc thu hồi từ Redis. Redis lỗi thì cho qua (fail-open) để sự cố Redis không làm tê liệt toàn hệ thống;
 * token vẫn bị giới hạn bởi thời gian sống ngắn (15 phút).
 */
public class RedisTokenRevocationChecker implements TokenRevocationChecker {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenRevocationChecker.class);

    private final StringRedisTemplate redisTemplate;

    public RedisTokenRevocationChecker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean isRevoked(AuthUser user) {
        if (user.userId() == null || user.isService()) {
            return false;
        }
        try {
            return TokenRevocationChecker.issuedBefore(user,
                    redisTemplate.opsForValue().get(TokenRevocationChecker.revokedBeforeKey(user.userId())));
        } catch (Exception e) {
            log.warn("Không kiểm tra được thu hồi token (Redis lỗi): {}", e.getMessage());
            return false;
        }
    }
}
