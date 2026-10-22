package com.bank.account.dto;

import java.time.LocalDateTime;

/** Alias số điện thoại của khách hàng; số điện thoại đã che. */
public record PhoneAliasResponse(
        String maskedPhone,
        String accountNumber,
        LocalDateTime registeredAt) {
}
