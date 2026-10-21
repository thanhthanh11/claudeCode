package com.bank.transaction.dto;

import com.bank.transaction.enums.DeadLetterStatusEnum;

import java.time.LocalDateTime;

/**
 * Không trả payload (có thể chứa số tài khoản, số tiền) — chỉ thông tin để định vị và xử lý.
 */
public record DeadLetterEventResponse(
        Long id,
        String dltTopic,
        String originalTopic,
        Integer partition,
        Long offset,
        String messageKey,
        String eventType,
        String errorMessage,
        DeadLetterStatusEnum status,
        String handledBy,
        LocalDateTime handledAt,
        LocalDateTime createdAt) {
}
