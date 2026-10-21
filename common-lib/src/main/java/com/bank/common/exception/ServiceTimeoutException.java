package com.bank.common.exception;

import org.springframework.http.HttpStatus;

public class ServiceTimeoutException extends BusinessException {

    public ServiceTimeoutException(String message) {
        super(HttpStatus.GATEWAY_TIMEOUT, "SERVICE_TIMEOUT", message);
    }
}
