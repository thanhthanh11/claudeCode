package com.bank.interbank.service;

import com.bank.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Xác thực webhook Napas: {@code X-Napas-Signature = hex(HMAC-SHA256(secret, timestamp + "." + body))},
 * {@code X-Napas-Timestamp} là epoch giây, lệch tối đa {@code bank.interbank.webhook.tolerance} (chống phát lại;
 * trùng trong khoảng này bị chặn bởi unique NAPAS_REF). So sánh hằng thời gian.
 * Secret Base64 tối thiểu 256 bit ({@code NAPAS_WEBHOOK_SECRET}); thiếu hoặc ngắn → service không khởi động.
 */
@Component
public class NapasSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKeySpec key;
    private final Duration tolerance;
    private final Clock clock;

    public NapasSignatureVerifier(@Value("${bank.interbank.webhook.secret:}") String base64Secret,
                                  @Value("${bank.interbank.webhook.tolerance:5m}") Duration tolerance) {
        this(base64Secret, tolerance, Clock.systemUTC());
    }

    NapasSignatureVerifier(String base64Secret, Duration tolerance, Clock clock) {
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(base64Secret == null ? "" : base64Secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("bank.interbank.webhook.secret phải là Base64");
        }
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("bank.interbank.webhook.secret (NAPAS_WEBHOOK_SECRET) phải tối thiểu 256 bit");
        }
        this.key = new SecretKeySpec(secret, ALGORITHM);
        this.tolerance = tolerance;
        this.clock = clock;
    }

    public void verify(String timestamp, String signature, String body) {
        if (timestamp == null || signature == null || body == null) {
            throw invalid("Thiếu chữ ký Napas");
        }
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw invalid("Timestamp không hợp lệ");
        }
        Duration skew = Duration.between(Instant.ofEpochSecond(epochSeconds), clock.instant()).abs();
        if (skew.compareTo(tolerance) > 0) {
            throw invalid("Yêu cầu đã hết hạn");
        }
        byte[] expected = sign(timestamp.trim() + "." + body);
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(signature.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            throw invalid("Chữ ký không hợp lệ");
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw invalid("Chữ ký không hợp lệ");
        }
    }

    /** Dùng cho test / công cụ giả lập Napas. */
    public String signHex(String timestamp, String body) {
        return HexFormat.of().formatHex(sign(timestamp + "." + body));
    }

    private byte[] sign(String content) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không tính được chữ ký HMAC", e);
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", message);
    }
}
