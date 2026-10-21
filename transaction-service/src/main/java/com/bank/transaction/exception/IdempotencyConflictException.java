package com.bank.transaction.exception;

import com.bank.common.exception.ConflictException;

public class IdempotencyConflictException extends ConflictException {

    public IdempotencyConflictException() {
        super("IDEMPOTENCY_KEY_CONFLICT", "Idempotency-Key đã được dùng cho một giao dịch khác");
    }
}
