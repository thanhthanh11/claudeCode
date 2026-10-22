package com.bank.transaction.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Chuyển tiền tới số điện thoại đã đăng ký alias nhận tiền. */
public record PhoneTransferRequest(
        @NotBlank @Size(max = 20) String fromAccountNumber,
        @NotBlank @Size(max = 20) String phone,
        @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @Size(max = 255) String description) {
}
