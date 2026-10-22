package com.bank.common.event;

import java.math.BigDecimal;

/**
 * Payload của INTERBANK_INBOUND_RECEIVED: tiền từ ngân hàng khác chuyển đến tài khoản trong hệ thống.
 * transaction-service tạo giao dịch INTERBANK_IN idempotent theo {@code napasRef}.
 */
public record InterbankInboundPayload(
        String napasRef,
        String sourceBankCode,
        String sourceAccount,
        String sourceName,
        String beneficiaryAccount,
        BigDecimal amount,
        String currency,
        String description) {
}
