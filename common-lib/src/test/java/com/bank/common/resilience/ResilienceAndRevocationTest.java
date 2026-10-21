package com.bank.common.resilience;

import com.bank.common.exception.BadRequestException;
import com.bank.common.exception.ServiceTimeoutException;
import com.bank.common.exception.ServiceUnavailableException;
import com.bank.common.security.AuthUser;
import com.bank.common.security.TokenRevocationChecker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilienceAndRevocationTest {

    private final Resilience4jRemoteCallGuard guard = new Resilience4jRemoteCallGuard(properties());

    @Test
    void circuitShouldOpenAfterRepeatedTimeouts() {
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> guard.call("account-service", () -> {
                calls.incrementAndGet();
                throw new ServiceTimeoutException("timeout");
            })).isInstanceOf(ServiceTimeoutException.class);
        }

        assertThatThrownBy(() -> guard.call("account-service", () -> {
            calls.incrementAndGet();
            return "ok";
        })).isInstanceOf(ServiceUnavailableException.class);
        assertThat(calls).hasValue(4);
    }

    @Test
    void businessErrorsShouldNotOpenCircuit() {
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> guard.call("customer-service", () -> {
                throw new BadRequestException("NOT_FOUND", "not found");
            })).isInstanceOf(BadRequestException.class);
        }

        assertThat(guard.call("customer-service", () -> "ok")).isEqualTo("ok");
    }

    @Test
    void tokenIssuedBeforeRevocationShouldBeRejected() {
        Instant issuedAt = Instant.parse("2026-10-21T00:00:00Z");
        AuthUser user = new AuthUser(1L, "user", Set.of("CUSTOMER"), Set.of(), issuedAt);

        assertThat(TokenRevocationChecker.issuedBefore(user, String.valueOf(issuedAt.getEpochSecond() + 1))).isTrue();
        assertThat(TokenRevocationChecker.issuedBefore(user, String.valueOf(issuedAt.getEpochSecond()))).isFalse();
        assertThat(TokenRevocationChecker.issuedBefore(user, null)).isFalse();
    }

    private static CircuitBreakerProperties properties() {
        CircuitBreakerProperties properties = new CircuitBreakerProperties();
        properties.setSlidingWindowSize(4);
        properties.setMinimumNumberOfCalls(4);
        properties.setFailureRateThreshold(50);
        properties.setWaitDurationInOpenState(Duration.ofMinutes(1));
        return properties;
    }
}
