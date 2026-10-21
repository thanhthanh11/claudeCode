package com.bank.common.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class EventSerializer {

    private EventSerializer() {
    }

    public static String write(ObjectMapper objectMapper, String eventType, Object payload) {
        try {
            return objectMapper.writeValueAsString(EventEnvelope.of(eventType, objectMapper.valueToTree(payload)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không thể serialize event " + eventType, e);
        }
    }

    public static EventEnvelope read(ObjectMapper objectMapper, String json) {
        try {
            return objectMapper.readValue(json, EventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Message Kafka không đúng định dạng EventEnvelope", e);
        }
    }

    public static <T> T payload(ObjectMapper objectMapper, EventEnvelope envelope, Class<T> type) {
        try {
            return objectMapper.treeToValue(envelope.payload(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload của event " + envelope.eventType() + " không hợp lệ", e);
        }
    }
}
