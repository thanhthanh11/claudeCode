package com.bank.common.security;

public final class RoleCodes {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String TELLER = "TELLER";
    public static final String ADMIN = "ADMIN";
    /** Role của token nội bộ giữa các service (không cấp cho người dùng). */
    public static final String SERVICE = "SERVICE";

    private RoleCodes() {
    }
}
