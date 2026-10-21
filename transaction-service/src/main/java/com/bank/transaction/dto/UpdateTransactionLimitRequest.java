package com.bank.transaction.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Giá trị null = không giới hạn.
 */
public record UpdateTransactionLimitRequest(
        @Positive @Digits(integer = 15, fraction = 4) BigDecimal perTxnMax,
        @Positive @Digits(integer = 15, fraction = 4) BigDecimal dailyMax,
        @Positive Integer dailyCountMax) {
}
