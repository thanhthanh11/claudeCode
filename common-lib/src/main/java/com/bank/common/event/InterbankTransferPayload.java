package com.bank.common.event;

import java.math.BigDecimal;

/**
 * Payload của INTERBANK_TRANSFER_REQUESTED: lệnh chuyển tiền ra ngân hàng khác sau khi đã trừ tiền tài khoản nguồn.
 * interbank-service xử lý idempotent theo {@code transactionRef}.
 */
public record InterbankTransferPayload(
        String transactionRef,
        String fromAccountNumber,
        String bankCode,
        String beneficiaryAccount,
        String beneficiaryName,
        BigDecimal amount,
        String currency,
        String description) {
}
