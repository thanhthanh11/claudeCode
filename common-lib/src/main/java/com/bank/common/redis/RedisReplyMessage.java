package com.bank.common.redis;

import com.fasterxml.jackson.databind.JsonNode;

public record RedisReplyMessage(
        String correlationId,
        boolean success,
        int status,
        String errorCode,
        String errorMessage,
        JsonNode data) {

    public static RedisReplyMessage success(String correlationId, JsonNode data) {
        return new RedisReplyMessage(correlationId, true, 200, null, null, data);
    }

    public static RedisReplyMessage failure(String correlationId, int status, String errorCode, String errorMessage) {
        return new RedisReplyMessage(correlationId, false, status, errorCode, errorMessage, null);
    }
}
