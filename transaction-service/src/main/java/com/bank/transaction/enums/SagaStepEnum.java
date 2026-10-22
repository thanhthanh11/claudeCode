package com.bank.transaction.enums;

/**
 * Bước hiện tại của Saga chuyển tiền:
 * DEBIT_REQUESTED → (DEBITED) → CREDIT_REQUESTED → CREDITED
 * hoặc khi cộng tiền lỗi: CREDIT_REQUESTED → COMPENSATION_REQUESTED → COMPENSATED.
 * Liên ngân hàng chiều đi: DEBIT_REQUESTED → INTERBANK_REQUESTED → (COMPLETED) hoặc bị từ chối → COMPENSATION_REQUESTED.
 */
public enum SagaStepEnum {
    DEBIT_REQUESTED,
    DEBITED,
    CREDIT_REQUESTED,
    CREDITED,
    /** Đã trừ tiền, chờ interbank-service báo kết quả chuyển sang ngân hàng khác. */
    INTERBANK_REQUESTED,
    COMPENSATION_REQUESTED,
    COMPENSATED
}
