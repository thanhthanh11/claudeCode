package com.bank.common.redis;

/**
 * Tên service đích và action trên kênh Redis request-reply.
 */
public final class RedisActions {

    public static final String CUSTOMER_SERVICE = "customer-service";
    public static final String ACCOUNT_SERVICE = "account-service";
    public static final String NOTIFICATION_SERVICE = "notification-service";
    public static final String INTERBANK_SERVICE = "interbank-service";

    /** payload: {@code CustomerLookupRequest(customerId)} → {@code CustomerInfoResponse} */
    public static final String CUSTOMER_GET_BY_ID = "customer.get-by-id";
    /** payload: {@code CustomerLookupRequest(userId)} → {@code CustomerInfoResponse} */
    public static final String CUSTOMER_GET_BY_USER_ID = "customer.get-by-user-id";
    /** payload: {@code AccountLookupRequest} → {@code AccountInfoResponse} */
    public static final String ACCOUNT_GET_BY_NUMBER = "account.get-by-number";
    /** payload: {@code LedgerLookupRequest} → {@code LedgerEntryInfo[]}. Chỉ service nội bộ / nhân viên. */
    public static final String ACCOUNT_LEDGER_BY_REF = "account.ledger-by-ref";
    /** payload: {@code PhoneAliasLookupRequest(phone)} → {@code PhoneAliasLookupResponse}. Mọi user đã đăng nhập / service. */
    public static final String ACCOUNT_RESOLVE_PHONE_ALIAS = "account.resolve-phone-alias";
    /** payload: {@code NameInquiryRequest} → {@code NameInquiryResponse} (tra tên chủ tài khoản ngân hàng khác qua Napas). */
    public static final String INTERBANK_NAME_INQUIRY = "interbank.name-inquiry";
    /** payload: {@code OtpDeliveryRequest} → null. Chỉ service nội bộ. */
    public static final String NOTIFICATION_SEND_OTP = "notification.send-otp";

    private RedisActions() {
    }
}
