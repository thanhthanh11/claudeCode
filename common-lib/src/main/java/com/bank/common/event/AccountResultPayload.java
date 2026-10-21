package com.bank.common.event;

import java.math.BigDecimal;

/**
 * Payload kết quả xử lý số dư (ACCOUNT_DEBITED, ACCOUNT_DEBIT_FAILED, ...).
 * {@code reason} chỉ có giá trị khi thất bại; {@code balanceAfter} chỉ có khi thành công.
 */
public record AccountResultPayload(
        String transactionRef,
        String accountNumber,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String reason) {
}
