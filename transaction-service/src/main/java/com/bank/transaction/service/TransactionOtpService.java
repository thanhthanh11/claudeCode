package com.bank.transaction.service;

import com.bank.common.dto.OtpDeliveryRequest;
import com.bank.common.redis.RedisActions;
import com.bank.common.redis.RedisRequestReplyClient;
import com.bank.transaction.config.OtpConfig;
import com.bank.transaction.entity.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * OTP xác nhận giao dịch. Chỉ lưu HMAC-SHA256 (khóa {@code bank.otp.hmac-secret}, gắn với transactionRef) trong
 * Redis, có TTL và giới hạn số lần nhập sai. OTP chỉ có 10^6 giá trị nên hash không khóa sẽ bị dò ngược ngay khi lộ
 * dữ liệu Redis; HMAC buộc kẻ tấn công phải có thêm secret. Mã OTP chỉ đi qua kênh Redis tới notification-service (không vào outbox / Kafka / log).
 */
@Slf4j
@Service
public class TransactionOtpService {

    private static final String KEY_PREFIX = "bank:otp:";
    private static final String RESEND_PREFIX = "bank:otp:resend:";
    // Đổi tên từ "hash" (SHA-256 cũ) để OTP phát hành trước khi nâng cấp được coi là hết hạn, không bị tính nhập sai
    private static final String FIELD_MAC = "mac";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MIN_SECRET_BYTES = 32;
    private static final String FIELD_ATTEMPTS = "attempts";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** KEYS[1] = key OTP, ARGV[1] = field mac, ARGV[2] = field attempts. Trả -1 nếu OTP không còn. */
    private static final RedisScript<Long> INCREMENT_ATTEMPTS_SCRIPT = RedisScript.of("""
            if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then return -1 end
            return redis.call('HINCRBY', KEYS[1], ARGV[2], 1)""", Long.class);

    public enum VerifyResult { VALID, INVALID, ATTEMPTS_EXCEEDED, EXPIRED }

    public record Verification(VerifyResult result, int remainingAttempts) {
    }

    private final StringRedisTemplate redisTemplate;
    private final RedisRequestReplyClient redisClient;
    private final OtpConfig config;
    private final SecretKeySpec hmacKey;

    public TransactionOtpService(StringRedisTemplate redisTemplate, RedisRequestReplyClient redisClient,
                                 OtpConfig config) {
        this.redisTemplate = redisTemplate;
        this.redisClient = redisClient;
        this.config = config;
        this.hmacKey = new SecretKeySpec(decodeSecret(config.getHmacSecret()), HMAC_ALGORITHM);
    }

    /** Sinh OTP mới (thay OTP cũ nếu có) và gửi cho khách hàng. */
    public void issue(Transaction transaction, Long customerId) {
        String ref = transaction.getTransactionRef();
        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        String key = KEY_PREFIX + ref;
        redisTemplate.delete(key);
        redisTemplate.opsForHash().putAll(key, Map.of(FIELD_MAC, sign(ref, otp), FIELD_ATTEMPTS, "0"));
        redisTemplate.expire(key, config.getTtl());
        redisClient.sendAsService(RedisActions.NOTIFICATION_SERVICE, RedisActions.NOTIFICATION_SEND_OTP,
                new OtpDeliveryRequest(customerId, ref, otp, transaction.getAmount(), transaction.getCurrency(),
                        config.getTtl().toSeconds()), Object.class);
        log.info("Đã gửi OTP cho giao dịch {}", ref);
    }

    /** Giới hạn số lần yêu cầu gửi lại OTP của một giao dịch. */
    public boolean tryConsumeResend(String transactionRef) {
        String key = RESEND_PREFIX + transactionRef;
        Long count = redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, Duration.ofHours(1));
        return count != null && count <= config.getMaxResends();
    }

    public Verification verify(String transactionRef, String otp) {
        String key = KEY_PREFIX + transactionRef;
        Object storedMac = redisTemplate.opsForHash().get(key, FIELD_MAC);
        if (storedMac == null) {
            return new Verification(VerifyResult.EXPIRED, 0);
        }
        // Script nguyên tử: chỉ HINCRBY khi OTP còn tồn tại — hai request nhập song song không vượt được giới hạn số lần,
        // và không tạo lại key thiếu TTL khi request khác vừa xác nhận đúng (đã xóa key)
        Long incremented = redisTemplate.execute(INCREMENT_ATTEMPTS_SCRIPT, List.of(key), FIELD_MAC, FIELD_ATTEMPTS);
        if (incremented == null || incremented < 0) {
            return new Verification(VerifyResult.EXPIRED, 0);
        }
        long attempts = incremented;
        if (attempts > config.getMaxAttempts()) {
            redisTemplate.delete(key);
            return new Verification(VerifyResult.ATTEMPTS_EXCEEDED, 0);
        }
        boolean matches = MessageDigest.isEqual(
                String.valueOf(storedMac).getBytes(StandardCharsets.UTF_8),
                sign(transactionRef, otp).getBytes(StandardCharsets.UTF_8));
        if (matches) {
            redisTemplate.delete(key);
            redisTemplate.delete(RESEND_PREFIX + transactionRef);
            return new Verification(VerifyResult.VALID, 0);
        }
        int remaining = (int) (config.getMaxAttempts() - attempts);
        if (remaining <= 0) {
            redisTemplate.delete(key);
            return new Verification(VerifyResult.ATTEMPTS_EXCEEDED, 0);
        }
        return new Verification(VerifyResult.INVALID, remaining);
    }

    private String sign(String transactionRef, String otp) {
        try {
            // Mac không thread-safe nên tạo mới mỗi lần (chi phí nhỏ, an toàn với virtual threads)
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal((transactionRef + ":" + otp).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không tính được HMAC cho OTP", e);
        }
    }

    /** Fail-fast khi khởi động: không để secret rỗng/ngắn âm thầm làm OTP yếu đi. */
    private static byte[] decodeSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Chưa cấu hình bank.otp.hmac-secret (OTP_HMAC_SECRET)");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("bank.otp.hmac-secret phải là chuỗi Base64", e);
        }
        if (key.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("bank.otp.hmac-secret phải dài tối thiểu 256 bit");
        }
        return key;
    }
}
