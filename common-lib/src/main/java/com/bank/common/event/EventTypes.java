package com.bank.common.event;

public final class EventTypes {

    // Lệnh gửi tới account-service
    public static final String ACCOUNT_DEBIT_REQUESTED = "ACCOUNT_DEBIT_REQUESTED";
    public static final String ACCOUNT_CREDIT_REQUESTED = "ACCOUNT_CREDIT_REQUESTED";
    public static final String ACCOUNT_REFUND_REQUESTED = "ACCOUNT_REFUND_REQUESTED";

    // Kết quả từ account-service
    public static final String ACCOUNT_DEBITED = "ACCOUNT_DEBITED";
    public static final String ACCOUNT_DEBIT_FAILED = "ACCOUNT_DEBIT_FAILED";
    public static final String ACCOUNT_CREDITED = "ACCOUNT_CREDITED";
    public static final String ACCOUNT_CREDIT_FAILED = "ACCOUNT_CREDIT_FAILED";
    public static final String ACCOUNT_REFUNDED = "ACCOUNT_REFUNDED";
    public static final String ACCOUNT_REFUND_FAILED = "ACCOUNT_REFUND_FAILED";

    // Sự kiện cho notification-service
    public static final String TRANSACTION_COMPLETED = "TRANSACTION_COMPLETED";
    public static final String TRANSACTION_FAILED = "TRANSACTION_FAILED";

    // Audit
    public static final String AUDIT_RECORDED = "AUDIT_RECORDED";

    private EventTypes() {
    }
}
