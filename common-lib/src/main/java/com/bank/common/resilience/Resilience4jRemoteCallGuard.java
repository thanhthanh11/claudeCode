package com.bank.common.resilience;

import com.bank.common.exception.BusinessException;
import com.bank.common.exception.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Mỗi service đích có một circuit breaker riêng (tạo khi gọi lần đầu).
 */
public class Resilience4jRemoteCallGuard implements RemoteCallGuard {

    private static final Logger log = LoggerFactory.getLogger(Resilience4jRemoteCallGuard.class);

    private final CircuitBreakerRegistry registry;

    public Resilience4jRemoteCallGuard(CircuitBreakerProperties properties) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(properties.getSlidingWindowSize())
                .minimumNumberOfCalls(properties.getMinimumNumberOfCalls())
                .failureRateThreshold(properties.getFailureRateThreshold())
                .waitDurationInOpenState(properties.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(properties.getPermittedCallsInHalfOpenState())
                .recordException(Resilience4jRemoteCallGuard::isFailure)
                .build();
        this.registry = CircuitBreakerRegistry.of(config);
        registry.getEventPublisher().onEntryAdded(event -> event.getAddedEntry().getEventPublisher()
                .onStateTransition(transition -> log.warn("Circuit breaker {}: {}",
                        transition.getCircuitBreakerName(), transition.getStateTransition())));
    }

    @Override
    public <T> T call(String target, Supplier<T> action) {
        CircuitBreaker circuitBreaker = registry.circuitBreaker(target);
        try {
            return circuitBreaker.executeSupplier(action);
        } catch (CallNotPermittedException e) {
            throw new ServiceUnavailableException("Service " + target + " tạm thời không khả dụng, vui lòng thử lại sau");
        }
    }

    /** Chỉ timeout / lỗi 5xx của service đích mới tính là lỗi; lỗi nghiệp vụ 4xx thì không. */
    static boolean isFailure(Throwable error) {
        if (error instanceof BusinessException business) {
            return business.getStatus().is5xxServerError();
        }
        return true;
    }

    public CircuitBreakerRegistry getRegistry() {
        return registry;
    }
}
