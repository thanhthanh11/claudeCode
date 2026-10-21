package com.bank.transaction.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * {@code percentRate} dạng thập phân: 0.0002 = 0,02%. {@code maxFee} null = không chặn trên.
 */
public record UpdateFeeScheduleRequest(
        @NotNull @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal fixedFee,
        @NotNull @PositiveOrZero @DecimalMax(value = "0.5") @Digits(integer = 1, fraction = 6) BigDecimal percentRate,
        @NotNull @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal minFee,
        @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal maxFee,
        @NotNull Boolean active) {
}
