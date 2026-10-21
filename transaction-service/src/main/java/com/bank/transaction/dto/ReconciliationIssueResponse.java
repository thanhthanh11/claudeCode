package com.bank.transaction.dto;

import com.bank.transaction.enums.ReconciliationIssueTypeEnum;

import java.time.LocalDateTime;

public record ReconciliationIssueResponse(
        Long id,
        String transactionRef,
        ReconciliationIssueTypeEnum issueType,
        String detail,
        LocalDateTime detectedAt,
        Boolean resolved,
        LocalDateTime resolvedAt,
        String note) {
}
