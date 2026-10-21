package com.bank.auth.exception;

import com.bank.common.exception.ResourceNotFoundException;

public class UserNotFoundException extends ResourceNotFoundException {

    public UserNotFoundException(Long id) {
        super("USER_NOT_FOUND", "Không tìm thấy người dùng #" + id);
    }
}
