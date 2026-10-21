package com.bank.transaction.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Nộp tiền / rút tiền mặt tại quầy (giao dịch viên thực hiện).
 */
public record CashTransactionRequest(
        @NotBlank @Size(max = 20) String accountNumber,
        @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @Size(max = 255) String description) {
}
