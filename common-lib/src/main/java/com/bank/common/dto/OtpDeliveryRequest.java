package com.bank.common.dto;

import java.math.BigDecimal;

/**
 * Yêu cầu notification-service gửi OTP xác nhận giao dịch. Chỉ đi qua kênh Redis (không lưu DB/Kafka)
 * để mã OTP không nằm lại trong outbox hay log của Kafka.
 */
public record OtpDeliveryRequest(
        Long customerId,
        String transactionRef,
        String otp,
        BigDecimal amount,
        String currency,
        long ttlSeconds) {

    /** Không để lộ OTP khi record bị in ra log. */
    @Override
    public String toString() {
        return "OtpDeliveryRequest[customerId=" + customerId + ", transactionRef=" + transactionRef + "]";
    }
}
