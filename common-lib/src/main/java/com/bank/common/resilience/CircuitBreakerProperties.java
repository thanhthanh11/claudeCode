package com.bank.common.resilience;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "bank.resilience.circuit-breaker")
public class CircuitBreakerProperties {

    private boolean enabled = true;
    /** Số lời gọi gần nhất dùng để tính tỉ lệ lỗi. */
    private int slidingWindowSize = 20;
    private int minimumNumberOfCalls = 10;
    /** Tỉ lệ lỗi (%) để mở mạch. */
    private float failureRateThreshold = 50;
    private Duration waitDurationInOpenState = Duration.ofSeconds(30);
    private int permittedCallsInHalfOpenState = 3;
}
