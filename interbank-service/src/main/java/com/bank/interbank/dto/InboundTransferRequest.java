package com.bank.interbank.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Body webhook Napas báo có tiền chuyển đến (đã xác thực chữ ký trước khi parse). */
public record InboundTransferRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{6,40}$") String napasRef,
        @NotBlank @Pattern(regexp = "^[0-9]{6,8}$") String sourceBankCode,
        @NotBlank @Pattern(regexp = "^[0-9A-Za-z]{6,34}$") String sourceAccount,
        @Size(max = 140) String sourceName,
        @NotBlank @Pattern(regexp = "^[0-9]{6,20}$") String beneficiaryAccount,
        @NotNull @Positive @Digits(integer = 15, fraction = 0) BigDecimal amount,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
        @Size(max = 210) String description) {
}
