package com.bank.common.security;

/**
 * Mã permission khớp với dữ liệu seed trong BANK_AUTH.PERMISSIONS.
 */
public final class PermissionCodes {

    public static final String ACCOUNT_READ = "ACCOUNT_READ";
    public static final String ACCOUNT_CREATE = "ACCOUNT_CREATE";
    public static final String ACCOUNT_UPDATE = "ACCOUNT_UPDATE";
    public static final String TRANSFER_CREATE = "TRANSFER_CREATE";
    public static final String TRANSACTION_READ = "TRANSACTION_READ";
    public static final String CUSTOMER_READ = "CUSTOMER_READ";
    public static final String CUSTOMER_MANAGE = "CUSTOMER_MANAGE";
    public static final String USER_MANAGE = "USER_MANAGE";
    /** Vận hành: xử lý giao dịch MANUAL_REVIEW, outbox, DLT, đối soát. */
    public static final String OPERATION_MANAGE = "OPERATION_MANAGE";

    private PermissionCodes() {
    }
}
