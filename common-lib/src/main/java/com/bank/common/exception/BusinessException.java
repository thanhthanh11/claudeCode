package com.bank.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Exception nghiệp vụ gốc: mang HTTP status và mã lỗi trả về cho client.
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
