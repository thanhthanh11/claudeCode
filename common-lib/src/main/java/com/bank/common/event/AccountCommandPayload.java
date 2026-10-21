package com.bank.common.event;

import java.math.BigDecimal;

/**
 * Payload của lệnh ACCOUNT_DEBIT_REQUESTED / ACCOUNT_CREDIT_REQUESTED / ACCOUNT_REFUND_REQUESTED.
 *
 * @param fee phí giao dịch (chỉ có ở lệnh trừ tiền và hoàn tiền). Trừ tiền: tài khoản bị trừ {@code amount + fee},
 *            phí được cộng vào tài khoản thu phí nội bộ. Hoàn tiền: hoàn lại cả {@code amount + fee}.
 *            Null (message cũ) được coi là 0.
 */
public record AccountCommandPayload(
        String transactionRef,
        String accountNumber,
        BigDecimal amount,
        String currency,
        String description,
        BigDecimal fee) {

    public AccountCommandPayload(String transactionRef, String accountNumber, BigDecimal amount, String currency,
                                 String description) {
        this(transactionRef, accountNumber, amount, currency, description, null);
    }

    public BigDecimal feeOrZero() {
        return fee == null ? BigDecimal.ZERO : fee;
    }

    /** Số tiền thực sự thay đổi trên tài khoản của lệnh trừ tiền / hoàn tiền. */
    public BigDecimal totalAmount() {
        return amount.add(feeOrZero());
    }
}
