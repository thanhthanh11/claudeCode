package com.bank.common.security;

import java.time.Instant;
import java.util.Set;

/**
 * Danh tính đã xác thực, giải mã từ JWT. Với token nội bộ của service: {@code userId = null},
 * {@code roles = [SERVICE]}. {@code issuedAt} (claim iat) dùng để từ chối token phát hành trước
 * thời điểm thu hồi (xem {@link TokenRevocationChecker}); null nếu không tạo từ token.
 */
public record AuthUser(Long userId, String username, Set<String> roles, Set<String> permissions, Instant issuedAt) {

    public AuthUser {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public AuthUser(Long userId, String username, Set<String> roles, Set<String> permissions) {
        this(userId, username, roles, permissions, null);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    /** Nhân viên ngân hàng (giao dịch viên hoặc quản trị). */
    public boolean isStaff() {
        return hasRole(RoleCodes.TELLER) || hasRole(RoleCodes.ADMIN);
    }

    public boolean isService() {
        return hasRole(RoleCodes.SERVICE);
    }
}
