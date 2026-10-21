package com.bank.transaction.dto;

import com.bank.transaction.enums.OutboxStatusEnum;

import java.time.LocalDateTime;

public record OutboxEventResponse(
        Long id,
        String aggregateId,
        String eventType,
        String topic,
        OutboxStatusEnum status,
        Integer retryCount,
        LocalDateTime createdAt,
        LocalDateTime publishedAt) {
}
