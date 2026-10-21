package com.bank.account.dto;

import com.bank.account.enums.HoldStatusEnum;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AccountHoldResponse(
        Long id,
        String accountNumber,
        BigDecimal amount,
        String reason,
        HoldStatusEnum status,
        LocalDateTime expiresAt,
        String createdBy,
        String releasedBy,
        LocalDateTime releasedAt,
        LocalDateTime createdAt) {
}
