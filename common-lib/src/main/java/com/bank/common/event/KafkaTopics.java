package com.bank.common.event;

public final class KafkaTopics {

    /** Lệnh trừ/cộng/hoàn tiền gửi tới account-service (key = transactionRef). */
    public static final String ACCOUNT_COMMANDS = "bank.account.commands";

    /** Kết quả xử lý số dư từ account-service gửi về transaction-service (Saga). */
    public static final String TRANSACTION_EVENTS = "bank.transaction.events";

    /** Sự kiện giao dịch hoàn tất/thất bại cho notification-service. */
    public static final String NOTIFICATION_EVENTS = "bank.notification.events";

    /** Audit hành động của người dùng/nhân viên, auth-service lưu lại (key = targetType:targetId). */
    public static final String AUDIT_EVENTS = "bank.audit.events";

    /** Hậu tố topic dead-letter (mặc định của DeadLetterPublishingRecoverer). */
    public static final String DLT_SUFFIX = ".DLT";

    private KafkaTopics() {
    }
}
