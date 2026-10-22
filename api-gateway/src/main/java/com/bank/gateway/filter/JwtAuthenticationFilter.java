package com.bank.gateway.filter;

import com.bank.common.dto.ApiResponse;
import com.bank.common.security.AuthUser;
import com.bank.common.security.JwtTokenProvider;
import com.bank.common.security.TokenRevocationChecker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Lớp bảo vệ đầu tiên: chặn request thiếu/sai JWT hoặc token đã bị thu hồi (khóa user, đổi role) trước khi tới
 * service. Service phía sau vẫn tự verify lại token (không tin header do gateway thêm vào).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    public static final String AUTH_USER_ATTRIBUTE = "bank.authUser";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            // Webhook Napas báo tiền đến: xác thực bằng chữ ký HMAC ở interbank-service, không có JWT
            "/api/interbank/inbound",
            "/actuator/health/**",
            "/actuator/info",
            // Tài liệu API (springdoc) — tắt bằng springdoc.*.enabled=false ngoài môi trường dev
            "/v3/api-docs/**",
            "/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/webjars/**");

    private final JwtTokenProvider tokenProvider;
    private final ObjectMapper objectMapper;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = UUID.randomUUID().toString();
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(REQUEST_ID_HEADER, requestId))
                .build();
        ServerWebExchange mutated = exchange.mutate().request(request).build();

        String path = request.getURI().getPath();
        if (PUBLIC_PATHS.stream().anyMatch(pattern -> pathMatcher.match(pattern, path))) {
            return chain.filter(mutated);
        }

        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return unauthorized(mutated, "Thiếu access token");
        }
        AuthUser user;
        try {
            user = tokenProvider.parseToken(header.substring(BEARER_PREFIX.length()));
        } catch (JwtException | IllegalArgumentException e) {
            return unauthorized(mutated, "Token không hợp lệ hoặc đã hết hạn");
        }
        if (user.isService()) {
            // Service token chỉ dùng nội bộ qua Redis, không được gọi từ bên ngoài
            return unauthorized(mutated, "Token không hợp lệ");
        }
        return isRevoked(user).flatMap(revoked -> {
            if (revoked) {
                return unauthorized(mutated, "Token đã bị thu hồi, vui lòng đăng nhập lại");
            }
            mutated.getAttributes().put(AUTH_USER_ATTRIBUTE, user);
            return chain.filter(mutated);
        });
    }

    /** Redis lỗi thì cho qua (fail-open), service phía sau vẫn kiểm tra lại. */
    private Mono<Boolean> isRevoked(AuthUser user) {
        if (user.userId() == null) {
            return Mono.just(false);
        }
        return redisTemplate.opsForValue().get(TokenRevocationChecker.revokedBeforeKey(user.userId()))
                .map(revokedBefore -> TokenRevocationChecker.issuedBefore(user, revokedBefore))
                .defaultIfEmpty(false)
                .onErrorResume(e -> {
                    log.warn("Không kiểm tra được thu hồi token (Redis lỗi): {}", e.getMessage());
                    return Mono.just(false);
                });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(ApiResponse.error("UNAUTHORIZED", message));
        } catch (JsonProcessingException e) {
            body = "{\"success\":false,\"code\":\"UNAUTHORIZED\"}".getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
