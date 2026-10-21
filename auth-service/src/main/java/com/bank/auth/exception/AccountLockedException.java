package com.bank.auth.exception;

import com.bank.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

public class AccountLockedException extends BusinessException {

    public AccountLockedException(String message) {
        super(HttpStatus.LOCKED, "ACCOUNT_LOCKED", message);
    }
}
