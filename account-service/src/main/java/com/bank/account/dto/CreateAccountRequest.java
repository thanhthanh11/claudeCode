package com.bank.account.dto;

import com.bank.account.enums.AccountTypeEnum;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateAccountRequest(
        @NotNull Long customerId,
        @NotNull AccountTypeEnum accountType,
        @Pattern(regexp = "^(VND|USD|EUR)$", message = "Loại tiền hỗ trợ: VND, USD, EUR") String currency) {

    public String currencyOrDefault() {
        return currency == null ? "VND" : currency;
    }
}
