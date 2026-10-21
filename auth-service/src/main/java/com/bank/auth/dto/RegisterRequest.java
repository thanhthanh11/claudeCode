package com.bank.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Pattern(regexp = "^[a-zA-Z0-9._]{4,50}$", message = "Tên đăng nhập 4-50 ký tự, chỉ gồm chữ, số, dấu chấm, gạch dưới")
        String username,

        @NotBlank
        @Size(min = 8, max = 100, message = "Mật khẩu phải từ 8 đến 100 ký tự")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "Mật khẩu phải có cả chữ và số")
        String password,

        @NotBlank
        @Email
        @Size(max = 150)
        String email) {
}
