package com.bank.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Định tuyến tới service qua Eureka (lb://). Mọi route API đều áp dụng rate limit.
 * Route {@code /api-docs/<service>} trỏ tới tài liệu OpenAPI của từng service để Swagger UI của gateway gộp lại
 * (không dùng /v3/api-docs/** vì trùng endpoint của springdoc trên gateway).
 */
@Configuration
public class GatewayRouteConfig {

    /** Service có tài liệu OpenAPI (springdoc) được gộp vào Swagger UI của gateway. */
    public static final List<String> DOCUMENTED_SERVICES = List.of(
            "auth-service", "customer-service", "account-service", "transaction-service", "notification-service",
            "interbank-service");

    @Bean
    public RouteLocator bankRoutes(RouteLocatorBuilder builder, RedisRateLimiter rateLimiter, KeyResolver keyResolver) {
        RouteLocatorBuilder.Builder routes = builder.routes()
                .route("auth-service", route -> route
                        .path("/api/auth/**", "/api/users/**", "/api/roles/**", "/api/audit-logs/**")
                        .filters(filters -> rateLimit(filters, rateLimiter, keyResolver))
                        .uri("lb://auth-service"))
                .route("customer-service", route -> route
                        .path("/api/customers/**")
                        .filters(filters -> rateLimit(filters, rateLimiter, keyResolver))
                        .uri("lb://customer-service"))
                .route("account-service", route -> route
                        .path("/api/accounts/**", "/api/admin/ledger-reconciliation/**")
                        .filters(filters -> rateLimit(filters, rateLimiter, keyResolver))
                        .uri("lb://account-service"))
                .route("transaction-service", route -> route
                        .path("/api/transactions/**", "/api/beneficiaries/**", "/api/scheduled-transfers/**",
                                "/api/transaction-limits/**", "/api/fee-schedules/**",
                                "/api/admin/transactions/**", "/api/admin/outbox/**", "/api/admin/dead-letters/**",
                                "/api/admin/transaction-reconciliation/**")
                        .filters(filters -> rateLimit(filters, rateLimiter, keyResolver))
                        .uri("lb://transaction-service"))
                .route("notification-service", route -> route
                        .path("/api/notifications/**", "/api/notification-templates/**")
                        .filters(filters -> rateLimit(filters, rateLimiter, keyResolver))
                        .uri("lb://notification-service"))
                .route("interbank-service", route -> route
                        .path("/api/interbank/**", "/api/admin/interbank/**")
                        .filters(filters -> rateLimit(filters, rateLimiter, keyResolver))
                        .uri("lb://interbank-service"));
        for (String service : DOCUMENTED_SERVICES) {
            routes.route(service + "-api-docs", route -> route
                    .path("/api-docs/" + service)
                    .filters(filters -> filters.setPath("/v3/api-docs"))
                    .uri("lb://" + service));
        }
        return routes.build();
    }

    private static GatewayFilterSpec rateLimit(GatewayFilterSpec filters, RedisRateLimiter rateLimiter,
                                               KeyResolver keyResolver) {
        return filters.requestRateLimiter(config -> config.setRateLimiter(rateLimiter).setKeyResolver(keyResolver));
    }
}
