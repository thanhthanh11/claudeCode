package com.bank.customer.exception;

import com.bank.common.exception.ConflictException;

public class CustomerAlreadyExistsException extends ConflictException {

    public CustomerAlreadyExistsException(String message) {
        super("CUSTOMER_ALREADY_EXISTS", message);
    }
}
