package com.bank.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Circuit breaker đang mở: service đích lỗi liên tục nên từ chối ngay thay vì chờ timeout.
 */
public class ServiceUnavailableException extends BusinessException {

    public ServiceUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", message);
    }
}
