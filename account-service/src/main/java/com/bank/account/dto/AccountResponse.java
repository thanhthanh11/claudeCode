package com.bank.account.dto;

import com.bank.account.enums.AccountStatusEnum;
import com.bank.account.enums.AccountTypeEnum;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AccountResponse(
        Long id,
        String accountNumber,
        Long customerId,
        AccountTypeEnum accountType,
        String currency,
        BigDecimal balance,
        BigDecimal availableBalance,
        AccountStatusEnum status,
        LocalDateTime openedAt,
        LocalDateTime closedAt) {
}
