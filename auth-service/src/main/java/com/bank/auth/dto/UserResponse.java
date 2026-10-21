package com.bank.auth.dto;

import com.bank.auth.enums.UserStatusEnum;

import java.time.LocalDateTime;
import java.util.Set;

public record UserResponse(
        Long id,
        String username,
        String email,
        UserStatusEnum status,
        Set<String> roles,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt) {
}
