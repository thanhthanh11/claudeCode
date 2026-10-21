package com.bank.notification.exception;

/**
 * Lỗi khi gửi thông báo qua nhà cung cấp email/SMS. {@code retryable=true} (lỗi tạm thời: mất kết nối, 5xx,
 * quá tải) → job thử lại theo lịch; {@code false} (người nhận/nội dung bị từ chối) → không thử lại.
 * Message không được chứa nội dung thông báo (có thể có OTP) vì được lưu vào ERROR_MESSAGE và ghi log.
 */
public class NotificationSendException extends RuntimeException {

    private final boolean retryable;

    public NotificationSendException(String message, Throwable cause) {
        this(message, cause, true);
    }

    public NotificationSendException(String message, boolean retryable) {
        this(message, null, retryable);
    }

    public NotificationSendException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
