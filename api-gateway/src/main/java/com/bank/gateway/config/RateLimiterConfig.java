package com.bank.gateway.config;

import com.bank.common.security.AuthUser;
import com.bank.gateway.filter.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * Giới hạn tần suất bằng Redis (token bucket): theo userId nếu đã đăng nhập, ngược lại theo IP.
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    public RedisRateLimiter redisRateLimiter(@Value("${bank.gateway.rate-limit.replenish-rate:20}") int replenishRate,
                                             @Value("${bank.gateway.rate-limit.burst-capacity:40}") int burstCapacity) {
        return new RedisRateLimiter(replenishRate, burstCapacity, 1);
    }

    @Bean
    public KeyResolver userOrIpKeyResolver() {
        return exchange -> {
            AuthUser user = exchange.getAttribute(JwtAuthenticationFilter.AUTH_USER_ATTRIBUTE);
            if (user != null && user.userId() != null) {
                return Mono.just("user:" + user.userId());
            }
            String ip = Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                    .map(InetSocketAddress::getAddress)
                    .map(address -> address.getHostAddress())
                    .orElse("unknown");
            return Mono.just("ip:" + ip);
        };
    }
}
