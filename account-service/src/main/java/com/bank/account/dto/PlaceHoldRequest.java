package com.bank.account.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Tạm giữ tiền trên tài khoản. {@code expiresAt} null = giữ tới khi giải tỏa thủ công.
 */
public record PlaceHoldRequest(
        @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotBlank @Size(max = 255) String reason,
        @Future LocalDateTime expiresAt) {
}
