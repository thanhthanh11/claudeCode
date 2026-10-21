package com.bank.common.event;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Vỏ bọc chung cho mọi message Kafka. {@code eventId} dùng để consumer chống xử lý trùng.
 */
public record EventEnvelope(String eventId, String eventType, Instant occurredAt, JsonNode payload) {

    public static EventEnvelope of(String eventType, JsonNode payload) {
        return new EventEnvelope(UUID.randomUUID().toString(), eventType, Instant.now(), payload);
    }
}
