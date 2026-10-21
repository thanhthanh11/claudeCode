package com.bank.transaction.enums;

public enum ReconciliationIssueTypeEnum {
    /** Bút toán sổ cái không khớp kết quả giao dịch */
    LEDGER_MISMATCH,
    /** Không tra được sổ cái (account-service lỗi/timeout) */
    LOOKUP_FAILED
}
