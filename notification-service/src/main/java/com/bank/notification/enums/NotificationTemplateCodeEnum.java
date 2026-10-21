package com.bank.notification.enums;

/**
 * Mã mẫu thông báo, khớp dữ liệu seed trong BANK_NOTIFICATION.NOTIFICATION_TEMPLATES.
 */
public enum NotificationTemplateCodeEnum {
    TRANSFER_SUCCESS,
    TRANSFER_FAILED,
    /** Người nhận chuyển khoản */
    TRANSFER_RECEIVED,
    DEPOSIT_SUCCESS,
    DEPOSIT_FAILED,
    WITHDRAWAL_SUCCESS,
    WITHDRAWAL_FAILED,
    /** OTP xác nhận giao dịch — nội dung lưu DB đã che mã OTP */
    TRANSACTION_OTP
}
