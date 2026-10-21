package com.bank.common.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Payload của TRANSACTION_COMPLETED / TRANSACTION_FAILED gửi cho notification-service.
 */
public record TransactionNotificationPayload(
        String transactionRef,
        String transactionType,
        String status,
        String fromAccountNumber,
        String toAccountNumber,
        BigDecimal amount,
        String currency,
        String reason,
        Instant occurredAt,
        BigDecimal fee) {

    public TransactionNotificationPayload(String transactionRef, String transactionType, String status,
                                          String fromAccountNumber, String toAccountNumber, BigDecimal amount,
                                          String currency, String reason, Instant occurredAt) {
        this(transactionRef, transactionType, status, fromAccountNumber, toAccountNumber, amount, currency, reason,
                occurredAt, null);
    }
}
