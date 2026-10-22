package com.bank.interbank.dto;

import com.bank.interbank.enums.OutboundStatusEnum;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Lệnh chuyển đi cho màn hình vận hành; số tài khoản đã che. */
public record OutboundTransferResponse(
        String transactionRef,
        String bankCode,
        String maskedBeneficiaryAccount,
        BigDecimal amount,
        String currency,
        OutboundStatusEnum status,
        String napasRef,
        String reason,
        int attempts,
        LocalDateTime nextAttemptAt,
        LocalDateTime createdAt,
        LocalDateTime completedAt) {
}
