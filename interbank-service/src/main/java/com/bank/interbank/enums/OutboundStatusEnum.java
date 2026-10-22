package com.bank.interbank.enums;

/**
 * Trạng thái lệnh chuyển đi: NEW chưa gửi Napas; UNKNOWN đã gửi nhưng chưa rõ kết quả (timeout / Napas đang xử lý)
 * → tra soát định kỳ; COMPLETED / REJECTED là kết quả cuối.
 */
public enum OutboundStatusEnum {
    NEW,
    UNKNOWN,
    COMPLETED,
    REJECTED;

    public boolean isFinal() {
        return this == COMPLETED || this == REJECTED;
    }
}
