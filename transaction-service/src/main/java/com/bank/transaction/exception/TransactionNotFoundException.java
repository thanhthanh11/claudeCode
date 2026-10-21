package com.bank.transaction.exception;

import com.bank.common.exception.ResourceNotFoundException;

public class TransactionNotFoundException extends ResourceNotFoundException {

    public TransactionNotFoundException(String transactionRef) {
        super("TRANSACTION_NOT_FOUND", "Không tìm thấy giao dịch " + transactionRef);
    }
}
