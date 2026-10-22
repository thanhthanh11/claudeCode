package com.bank.common.event;

/**
 * Payload của INTERBANK_TRANSFER_COMPLETED / INTERBANK_TRANSFER_REJECTED.
 * {@code napasRef} là mã tham chiếu phía Napas (có khi Napas đã tiếp nhận); {@code reason} chỉ có khi bị từ chối.
 */
public record InterbankResultPayload(
        String transactionRef,
        String napasRef,
        String reason) {
}
