package com.bank.common.event;

/**
 * Payload của INTERBANK_INBOUND_SETTLED (đã cộng tiền) / INTERBANK_INBOUND_RETURNED (không cộng được, Napas trả
 * tiền về ngân hàng gửi). {@code reason} chỉ có khi trả lại.
 */
public record InterbankInboundResultPayload(
        String napasRef,
        String transactionRef,
        String reason) {
}
