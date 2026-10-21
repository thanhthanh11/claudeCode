package com.bank.common.config;

import com.bank.common.resilience.CircuitBreakerProperties;
import com.bank.common.resilience.RemoteCallGuard;
import com.bank.common.resilience.Resilience4jRemoteCallGuard;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Circuit breaker (Resilience4j) cho lời gọi giữa các service: Redis request-reply và REST nội bộ.
 */
@AutoConfiguration
@ConditionalOnClass(name = "io.github.resilience4j.circuitbreaker.CircuitBreaker")
@ConditionalOnProperty(prefix = "bank.resilience.circuit-breaker", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(CircuitBreakerProperties.class)
public class CommonResilienceConfig {

    @Bean
    @ConditionalOnMissingBean
    public RemoteCallGuard remoteCallGuard(CircuitBreakerProperties properties) {
        return new Resilience4jRemoteCallGuard(properties);
    }
}
