package com.bank.transaction.enums;

/**
 * Bước hiện tại của Saga chuyển tiền:
 * DEBIT_REQUESTED → (DEBITED) → CREDIT_REQUESTED → CREDITED
 * hoặc khi cộng tiền lỗi: CREDIT_REQUESTED → COMPENSATION_REQUESTED → COMPENSATED.
 */
public enum SagaStepEnum {
    DEBIT_REQUESTED,
    DEBITED,
    CREDIT_REQUESTED,
    CREDITED,
    COMPENSATION_REQUESTED,
    COMPENSATED
}
