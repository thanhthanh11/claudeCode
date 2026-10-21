package com.bank.account.dto;

import com.bank.account.enums.EntryTypeEnum;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record LedgerEntryResponse(
        Long id,
        String transactionRef,
        EntryTypeEnum entryType,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String description,
        LocalDateTime createdAt) {
}
