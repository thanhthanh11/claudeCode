package com.bank.transaction.enums;

public enum TransactionStatusEnum {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    /** Đã trừ tiền nhưng cộng tiền thất bại → đã hoàn tiền về tài khoản nguồn. */
    COMPENSATED,
    /** Gửi lại lệnh quá số lần cho phép mà vẫn chưa có kết quả → chờ nhân viên xử lý. Kết quả đến muộn vẫn được áp dụng. */
    MANUAL_REVIEW;

    public boolean isFinal() {
        return this == COMPLETED || this == FAILED || this == COMPENSATED;
    }
}
