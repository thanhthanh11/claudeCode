package com.bank.interbank.service;

import com.bank.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NapasSignatureVerifierTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);
    private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
    private static final String BODY = "{\"napasRef\":\"NP000001\",\"amount\":500000}";

    private final NapasSignatureVerifier verifier =
            new NapasSignatureVerifier(SECRET, Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void shouldAcceptValidSignature() {
        String timestamp = String.valueOf(NOW.getEpochSecond());

        assertThatCode(() -> verifier.verify(timestamp, verifier.signHex(timestamp, BODY), BODY))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectTamperedBody() {
        String timestamp = String.valueOf(NOW.getEpochSecond());
        String signature = verifier.signHex(timestamp, BODY);

        assertInvalid(() -> verifier.verify(timestamp, signature, BODY.replace("500000", "900000")));
    }

    @Test
    void shouldRejectExpiredTimestamp() {
        String timestamp = String.valueOf(NOW.minus(Duration.ofMinutes(6)).getEpochSecond());

        assertInvalid(() -> verifier.verify(timestamp, verifier.signHex(timestamp, BODY), BODY));
    }

    @Test
    void shouldRejectMissingOrMalformedSignature() {
        String timestamp = String.valueOf(NOW.getEpochSecond());

        assertInvalid(() -> verifier.verify(timestamp, null, BODY));
        assertInvalid(() -> verifier.verify(timestamp, "not-hex", BODY));
        assertInvalid(() -> verifier.verify("abc", verifier.signHex("abc", BODY), BODY));
    }

    @Test
    void shouldNotStartWithShortSecret() {
        String shortSecret = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new NapasSignatureVerifier(shortSecret, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_SIGNATURE");
    }
}
