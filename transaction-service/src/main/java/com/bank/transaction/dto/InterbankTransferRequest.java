package com.bank.transaction.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Chuyển tiền ra ngân hàng khác qua Napas 247 (chỉ VND, không có phần thập phân). */
public record InterbankTransferRequest(
        @NotBlank @Size(max = 20) String fromAccountNumber,
        @NotBlank @Pattern(regexp = "^[0-9]{6,8}$", message = "Mã ngân hàng là BIN Napas 6-8 chữ số") String bankCode,
        @NotBlank @Pattern(regexp = "^[0-9A-Za-z]{6,34}$", message = "Số tài khoản người nhận không hợp lệ")
        String beneficiaryAccount,
        @NotNull @Positive @Digits(integer = 15, fraction = 0) BigDecimal amount,
        @Size(max = 210) String description) {
}
