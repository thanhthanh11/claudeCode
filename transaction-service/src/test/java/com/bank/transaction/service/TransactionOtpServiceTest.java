package com.bank.transaction.service;

import com.bank.common.dto.OtpDeliveryRequest;
import com.bank.common.redis.RedisActions;
import com.bank.common.redis.RedisRequestReplyClient;
import com.bank.transaction.config.OtpConfig;
import com.bank.transaction.entity.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * OTP lưu trong một Redis hash giả lập bằng Map để kiểm tra hash + đếm số lần nhập sai.
 */
@ExtendWith(MockitoExtension.class)
class TransactionOtpServiceTest {

    private static final String REF = "TX2026102100000001";
    private static final String KEY = "bank:otp:" + REF;
    private static final String SECRET = Base64.getEncoder()
            .encodeToString("test-only-otp-hmac-secret-0123456789".getBytes(StandardCharsets.UTF_8));

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private RedisRequestReplyClient redisClient;

    private final Map<Object, Object> store = new HashMap<>();
    private TransactionOtpService otpService;

    @BeforeEach
    void setUp() {
        otpService = new TransactionOtpService(redisTemplate, redisClient, config(SECRET));
        lenient().when(redisTemplate.<Object, Object>opsForHash()).thenReturn(hashOperations);
        lenient().doAnswer(inv -> {
            store.putAll(inv.getArgument(1));
            return null;
        }).when(hashOperations).putAll(eq(KEY), anyMap());
        lenient().when(hashOperations.get(eq(KEY), any())).thenAnswer(inv -> store.get(inv.getArgument(1)));
        // Giả lập Lua script: HINCRBY attempts chỉ khi field mac còn tồn tại
        lenient().when(redisTemplate.execute(any(RedisScript.class), eq(List.of(KEY)), any(), any()))
                .thenAnswer(inv -> {
                    if (!store.containsKey(inv.getArgument(2))) {
                        return -1L;
                    }
                    Object field = inv.getArgument(3);
                    long value = Long.parseLong(String.valueOf(store.getOrDefault(field, "0"))) + 1;
                    store.put(field, String.valueOf(value));
                    return value;
                });
        lenient().when(redisTemplate.delete(KEY)).thenAnswer(inv -> {
            store.clear();
            return true;
        });
    }

    @Test
    void issuedOtpShouldBeVerifiedOnceAndNotStoredInPlainText() {
        String otp = issue();

        assertThat(store.values()).doesNotContain(otp);
        assertThat(otpService.verify(REF, otp).result()).isEqualTo(TransactionOtpService.VerifyResult.VALID);
        // OTP chỉ dùng được một lần
        assertThat(otpService.verify(REF, otp).result()).isEqualTo(TransactionOtpService.VerifyResult.EXPIRED);
    }

    @Test
    void wrongOtpShouldCountAttemptsUntilExhausted() {
        String otp = issue();
        String wrong = otp.equals("000000") ? "111111" : "000000";

        assertThat(otpService.verify(REF, wrong).remainingAttempts()).isEqualTo(2);
        assertThat(otpService.verify(REF, wrong).remainingAttempts()).isEqualTo(1);
        assertThat(otpService.verify(REF, wrong).result())
                .isEqualTo(TransactionOtpService.VerifyResult.ATTEMPTS_EXCEEDED);
        // Đã hủy OTP: nhập đúng cũng không còn hiệu lực
        assertThat(otpService.verify(REF, otp).result()).isEqualTo(TransactionOtpService.VerifyResult.EXPIRED);
    }

    @Test
    void shouldStoreHmacInsteadOfPlainSha256() throws Exception {
        String otp = issue();

        String plainSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((REF + ":" + otp).getBytes(StandardCharsets.UTF_8)));
        assertThat(store).containsKey("mac").doesNotContainKey("hash");
        assertThat(String.valueOf(store.get("mac"))).isNotEqualTo(plainSha256).doesNotContain(otp);
    }

    @Test
    void shouldRejectOtpSignedWithAnotherSecret() {
        String otp = issue();
        String otherSecret = Base64.getEncoder()
                .encodeToString("another-otp-hmac-secret-9876543210xx".getBytes(StandardCharsets.UTF_8));
        TransactionOtpService otherService = new TransactionOtpService(redisTemplate, redisClient, config(otherSecret));

        assertThat(otherService.verify(REF, otp).result()).isEqualTo(TransactionOtpService.VerifyResult.INVALID);
    }

    @Test
    void shouldTreatLegacySha256OtpAsExpired() {
        store.put("hash", "legacy");
        store.put("attempts", "0");

        assertThat(otpService.verify(REF, "123456").result()).isEqualTo(TransactionOtpService.VerifyResult.EXPIRED);
    }

    @Test
    void shouldFailFastWhenSecretIsMissingOrTooShort() {
        String shortSecret = Base64.getEncoder().encodeToString("too-short".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new TransactionOtpService(redisTemplate, redisClient, config(null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TransactionOtpService(redisTemplate, redisClient, config("not base64!")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TransactionOtpService(redisTemplate, redisClient, config(shortSecret)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static OtpConfig config(String secret) {
        OtpConfig config = new OtpConfig();
        config.setMaxAttempts(3);
        config.setHmacSecret(secret);
        return config;
    }

    private String issue() {
        Transaction transaction = new Transaction();
        transaction.setTransactionRef(REF);
        transaction.setAmount(new BigDecimal("20000000"));
        transaction.setCurrency("VND");
        otpService.issue(transaction, 1L);
        ArgumentCaptor<OtpDeliveryRequest> request = ArgumentCaptor.forClass(OtpDeliveryRequest.class);
        verify(redisClient).sendAsService(eq(RedisActions.NOTIFICATION_SERVICE), eq(RedisActions.NOTIFICATION_SEND_OTP),
                request.capture(), eq(Object.class));
        assertThat(request.getValue().otp()).matches("\\d{6}");
        assertThat(request.getValue().toString()).doesNotContain(request.getValue().otp());
        return request.getValue().otp();
    }
}
