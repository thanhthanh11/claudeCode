package com.bank.common.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Optional<AuthUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    public static AuthUser requireCurrentUser() {
        return currentUser().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Chưa xác thực"));
    }

    /** Access token gốc của request hiện tại, dùng để chuyển tiếp khi gọi service khác. */
    public static Optional<String> currentToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getCredentials() instanceof String token) {
            return Optional.of(token);
        }
        return Optional.empty();
    }

    /** Role → {@code ROLE_<code>} (dùng với hasRole), permission giữ nguyên (dùng với hasAuthority). */
    public static List<GrantedAuthority> toAuthorities(AuthUser user) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        user.roles().forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        user.permissions().forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
        return authorities;
    }

    public static Authentication toAuthentication(AuthUser user, String token) {
        return UsernamePasswordAuthenticationToken.authenticated(user, token, toAuthorities(user));
    }
}
