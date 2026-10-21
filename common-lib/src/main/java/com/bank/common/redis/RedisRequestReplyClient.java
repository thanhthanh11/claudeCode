package com.bank.common.redis;

import com.bank.common.crypto.AesGcmCipher;
import com.bank.common.exception.RemoteServiceException;
import com.bank.common.exception.ServiceTimeoutException;
import com.bank.common.resilience.RemoteCallGuard;
import com.bank.common.security.JwtTokenProvider;
import com.bank.common.security.SecurityUtils;
import com.bank.common.tracing.TraceContextPropagator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.UUID;

/**
 * Gửi request tới service khác qua Redis và chờ response:
 * LPUSH {@code bank:rr:queue:<service>} → service đích xử lý → LPUSH {@code bank:rr:reply:<correlationId>}
 * → bên gọi BRPOP key reply (có timeout). Mỗi service đích có circuit breaker riêng ({@link RemoteCallGuard}).
 * Toàn bộ message request / reply (token, payload, dữ liệu trả về như SĐT, email, OTP) được mã hóa AES-256-GCM
 * bằng khóa con {@code redis} trước khi đẩy vào Redis.
 */
public class RedisRequestReplyClient {

    private static final Logger log = LoggerFactory.getLogger(RedisRequestReplyClient.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final JwtTokenProvider tokenProvider;
    private final RedisRequestReplyProperties properties;
    private final String serviceName;
    private final RemoteCallGuard callGuard;
    private final TraceContextPropagator tracePropagator;
    private final AesGcmCipher cipher;

    public RedisRequestReplyClient(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                   JwtTokenProvider tokenProvider, RedisRequestReplyProperties properties,
                                   String serviceName, RemoteCallGuard callGuard,
                                   TraceContextPropagator tracePropagator, AesGcmCipher cipher) {
        this.cipher = cipher;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.tokenProvider = tokenProvider;
        this.properties = properties;
        this.serviceName = serviceName;
        this.callGuard = callGuard;
        this.tracePropagator = tracePropagator;
    }

    public <T> T send(String targetService, String action, Object payload, Class<T> responseType) {
        return send(targetService, action, payload, responseType, properties.getDefaultTimeout());
    }

    public <T> T send(String targetService, String action, Object payload, Class<T> responseType, Duration timeout) {
        // Chuyển tiếp token người dùng nếu có; nếu không (vd. trong Kafka consumer) dùng service token
        String token = SecurityUtils.currentToken().orElseGet(() -> tokenProvider.generateServiceToken(serviceName));
        return callGuard.call(targetService, () -> exchange(targetService, action, payload, responseType, timeout, token));
    }

    /**
     * Luôn gọi bằng service token (không chuyển tiếp token người dùng) — cho action chỉ dành cho service nội bộ,
     * vd. gửi OTP. Bên gọi phải tự kiểm tra quyền của người dùng trước khi gọi.
     */
    public <T> T sendAsService(String targetService, String action, Object payload, Class<T> responseType) {
        String token = tokenProvider.generateServiceToken(serviceName);
        return callGuard.call(targetService, () -> exchange(targetService, action, payload, responseType,
                properties.getDefaultTimeout(), token));
    }

    private <T> T exchange(String targetService, String action, Object payload, Class<T> responseType,
                           Duration timeout, String token) {
        String correlationId = UUID.randomUUID().toString();
        String replyKey = properties.replyKey(correlationId);

        RedisRequestMessage request = new RedisRequestMessage(correlationId, action, serviceName, replyKey, token,
                objectMapper.valueToTree(payload), System.currentTimeMillis(), timeout.toMillis(),
                tracePropagator.currentContext());
        redisTemplate.opsForList().leftPush(properties.queueKey(targetService), toJson(request));

        String rawReply = redisTemplate.opsForList().rightPop(replyKey, timeout);
        if (rawReply == null) {
            log.warn("Hết thời gian chờ response từ {} cho action {} (correlationId={})",
                    targetService, action, correlationId);
            throw new ServiceTimeoutException("Service " + targetService + " không phản hồi kịp thời");
        }

        RedisReplyMessage reply = fromJson(rawReply);
        if (!reply.success()) {
            HttpStatus status = HttpStatus.resolve(reply.status());
            throw new RemoteServiceException(status == null ? HttpStatus.BAD_GATEWAY : status,
                    reply.errorCode(), reply.errorMessage());
        }
        if (reply.data() == null || reply.data().isNull()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(reply.data(), responseType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Response từ " + targetService + " không đúng định dạng", e);
        }
    }

    private String toJson(RedisRequestMessage request) {
        try {
            return cipher.encrypt(objectMapper.writeValueAsString(request));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không thể serialize request Redis", e);
        }
    }

    private RedisReplyMessage fromJson(String raw) {
        try {
            return objectMapper.readValue(cipher.decrypt(raw), RedisReplyMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Reply Redis không đúng định dạng", e);
        }
    }
}
