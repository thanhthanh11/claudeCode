package com.bank.customer.exception;

import com.bank.common.exception.ResourceNotFoundException;

public class CustomerNotFoundException extends ResourceNotFoundException {

    public CustomerNotFoundException(String message) {
        super("CUSTOMER_NOT_FOUND", message);
    }
}
