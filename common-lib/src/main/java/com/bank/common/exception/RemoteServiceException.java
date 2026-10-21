package com.bank.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Lỗi do service khác trả về (qua Redis request-reply hoặc REST nội bộ); giữ nguyên status/mã lỗi gốc.
 */
public class RemoteServiceException extends BusinessException {

    public RemoteServiceException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }
}
