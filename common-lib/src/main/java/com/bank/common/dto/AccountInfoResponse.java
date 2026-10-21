package com.bank.common.dto;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Thông tin tài khoản trả về qua kênh Redis request-reply (account-service → service khác).
 * Số dư bị ẩn (null) khi bên gọi không phải chủ tài khoản / nhân viên / service nội bộ.
 */
public record AccountInfoResponse(
        Long id,
        String accountNumber,
        Long customerId,
        String accountType,
        String currency,
        BigDecimal balance,
        BigDecimal availableBalance,
        String status) implements Serializable {

    public AccountInfoResponse withoutBalances() {
        return new AccountInfoResponse(id, accountNumber, customerId, accountType, currency, null, null, status);
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
