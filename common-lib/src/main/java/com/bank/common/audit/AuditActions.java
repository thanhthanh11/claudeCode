package com.bank.common.audit;

/**
 * Mã hành động audit.
 */
public final class AuditActions {

    // auth-service
    public static final String USER_ROLES_UPDATED = "USER_ROLES_UPDATED";
    public static final String USER_STATUS_UPDATED = "USER_STATUS_UPDATED";
    // customer-service
    public static final String CUSTOMER_CREATED = "CUSTOMER_CREATED";
    public static final String CUSTOMER_PROFILE_UPDATED = "CUSTOMER_PROFILE_UPDATED";
    public static final String CUSTOMER_STATUS_UPDATED = "CUSTOMER_STATUS_UPDATED";
    public static final String CUSTOMER_KYC_STATUS_UPDATED = "CUSTOMER_KYC_STATUS_UPDATED";
    public static final String KYC_DOCUMENT_REVIEWED = "KYC_DOCUMENT_REVIEWED";
    // account-service
    public static final String ACCOUNT_OPENED = "ACCOUNT_OPENED";
    public static final String ACCOUNT_STATUS_UPDATED = "ACCOUNT_STATUS_UPDATED";
    public static final String ACCOUNT_HOLD_PLACED = "ACCOUNT_HOLD_PLACED";
    public static final String ACCOUNT_HOLD_RELEASED = "ACCOUNT_HOLD_RELEASED";
    public static final String LEDGER_RECONCILIATION_RUN = "LEDGER_RECONCILIATION_RUN";
    // transaction-service
    public static final String CASH_TRANSACTION_CREATED = "CASH_TRANSACTION_CREATED";
    public static final String STAFF_TRANSFER_CREATED = "STAFF_TRANSFER_CREATED";
    public static final String TRANSACTION_RETRIED = "TRANSACTION_RETRIED";
    public static final String OUTBOX_EVENT_REPLAYED = "OUTBOX_EVENT_REPLAYED";
    public static final String DEAD_LETTER_REPLAYED = "DEAD_LETTER_REPLAYED";
    public static final String DEAD_LETTER_IGNORED = "DEAD_LETTER_IGNORED";
    public static final String TRANSACTION_LIMIT_UPDATED = "TRANSACTION_LIMIT_UPDATED";
    public static final String FEE_SCHEDULE_UPDATED = "FEE_SCHEDULE_UPDATED";
    public static final String TRANSACTION_RECONCILIATION_RUN = "TRANSACTION_RECONCILIATION_RUN";
    // notification-service
    public static final String NOTIFICATION_TEMPLATE_UPDATED = "NOTIFICATION_TEMPLATE_UPDATED";

    private AuditActions() {
    }
}
