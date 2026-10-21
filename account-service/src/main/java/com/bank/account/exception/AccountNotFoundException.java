package com.bank.account.exception;

import com.bank.common.exception.ResourceNotFoundException;

public class AccountNotFoundException extends ResourceNotFoundException {

    public AccountNotFoundException(String accountNumber) {
        super("ACCOUNT_NOT_FOUND", "Không tìm thấy tài khoản " + accountNumber);
    }
}
