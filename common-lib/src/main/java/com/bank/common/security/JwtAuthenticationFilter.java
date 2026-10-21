package com.bank.common.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Đọc {@code Authorization: Bearer <token>}, verify JWT và đặt {@link AuthUser} vào SecurityContext.
 * Service token chỉ hợp lệ trên kênh Redis nội bộ nên bị bỏ qua ở REST; token đã bị thu hồi cũng bị bỏ qua.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final TokenRevocationChecker revocationChecker;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider) {
        this(tokenProvider, user -> false);
    }

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, TokenRevocationChecker revocationChecker) {
        this.tokenProvider = tokenProvider;
        this.revocationChecker = revocationChecker;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                AuthUser user = tokenProvider.parseToken(token);
                if (!user.isService() && !revocationChecker.isRevoked(user)) {
                    SecurityContextHolder.getContext().setAuthentication(SecurityUtils.toAuthentication(user, token));
                }
            } catch (JwtException | IllegalArgumentException e) {
                log.debug("JWT không hợp lệ: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
