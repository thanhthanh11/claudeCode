package com.bank.common.security;

/**
 * Kiểm tra access token của người dùng đã bị thu hồi chưa (khóa user, đổi role). auth-service ghi
 * {@link #revokedBeforeKey(Long)} = epoch giây lúc thu hồi; token có {@code iat} nhỏ hơn giá trị này bị từ chối.
 * Key có TTL bằng thời gian sống của access token nên tự hết hạn khi mọi token cũ đã hết hạn.
 */
public interface TokenRevocationChecker {

    String KEY_PREFIX = "bank:auth:revoked-before:";

    boolean isRevoked(AuthUser user);

    static String revokedBeforeKey(Long userId) {
        return KEY_PREFIX + userId;
    }

    /** Dùng chung cho filter servlet và gateway (reactive): token phát hành trước mốc thu hồi. */
    static boolean issuedBefore(AuthUser user, String revokedBeforeEpochSeconds) {
        if (revokedBeforeEpochSeconds == null || user.issuedAt() == null) {
            return false;
        }
        try {
            return user.issuedAt().getEpochSecond() < Long.parseLong(revokedBeforeEpochSeconds);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
