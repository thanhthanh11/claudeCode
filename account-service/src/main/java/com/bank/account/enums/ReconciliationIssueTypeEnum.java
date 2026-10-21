package com.bank.account.enums;

public enum ReconciliationIssueTypeEnum {
    /** BALANCE khác tổng CREDIT - DEBIT trong sổ cái */
    BALANCE_MISMATCH,
    /** AVAILABLE_BALANCE khác BALANCE - tổng hold ACTIVE */
    AVAILABLE_BALANCE_MISMATCH
}
