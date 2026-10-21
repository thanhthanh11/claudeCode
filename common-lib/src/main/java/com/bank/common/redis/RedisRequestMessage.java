package com.bank.common.redis;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Request đẩy vào queue Redis của service đích.
 *
 * @param correlationId ghép response với request
 * @param replyTo       key Redis (list) mà bên gọi đang chờ response
 * @param token         JWT của người dùng (chuyển tiếp) hoặc service token
 * @param createdAt     epoch millis lúc tạo; worker bỏ qua request đã quá {@code timeoutMs}
 * @param traceContext  header trace (traceparent) để worker nối tiếp trace của bên gọi
 */
public record RedisRequestMessage(
        String correlationId,
        String action,
        String sourceService,
        String replyTo,
        String token,
        JsonNode payload,
        long createdAt,
        long timeoutMs,
        Map<String, String> traceContext) {

    public boolean isExpired(long nowMillis) {
        return nowMillis > createdAt + timeoutMs;
    }
}
