package com.bank.account.dto;

import com.bank.account.enums.ReconciliationIssueTypeEnum;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ReconciliationIssueResponse(
        Long id,
        String accountNumber,
        ReconciliationIssueTypeEnum issueType,
        BigDecimal expectedAmount,
        BigDecimal actualAmount,
        LocalDateTime detectedAt,
        Boolean resolved,
        LocalDateTime resolvedAt,
        String note) {
}
