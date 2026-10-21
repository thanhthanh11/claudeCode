package com.bank.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Dùng cho cả làm mới token và đăng xuất.
 */
public record RefreshTokenRequest(@NotBlank String refreshToken) {
}
