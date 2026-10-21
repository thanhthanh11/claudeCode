package com.bank.account.enums;

public enum AccountTypeEnum {
    /** Tài khoản thanh toán, số tài khoản bắt đầu bằng 10 */
    PAYMENT("10"),
    /** Tài khoản tiết kiệm, số tài khoản bắt đầu bằng 20 */
    SAVINGS("20"),
    /** Tài khoản nội bộ của ngân hàng (thu phí...), không mở qua API và không thuộc khách hàng */
    INTERNAL("99");

    private final String numberPrefix;

    AccountTypeEnum(String numberPrefix) {
        this.numberPrefix = numberPrefix;
    }

    public String getNumberPrefix() {
        return numberPrefix;
    }
}
