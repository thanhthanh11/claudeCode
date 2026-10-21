package com.bank.transaction.dto;

import com.bank.transaction.enums.SagaStepEnum;
import com.bank.transaction.enums.TransactionStatusEnum;
import com.bank.transaction.enums.TransactionTypeEnum;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionResponse(
        Long id,
        String transactionRef,
        TransactionTypeEnum transactionType,
        String fromAccountNumber,
        String toAccountNumber,
        BigDecimal amount,
        BigDecimal fee,
        String currency,
        TransactionStatusEnum status,
        SagaStepEnum sagaStep,
        String description,
        String failureReason,
        LocalDateTime createdAt,
        LocalDateTime completedAt) {
}
