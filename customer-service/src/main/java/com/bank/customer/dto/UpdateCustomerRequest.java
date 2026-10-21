package com.bank.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Chỉ cập nhật các trường khác null.
 */
public record UpdateCustomerRequest(
        @Size(max = 150) String fullName,
        @Pattern(regexp = "^(\\+84|0)\\d{9,10}$", message = "Số điện thoại không hợp lệ") String phone,
        @Email @Size(max = 150) String email,
        @Size(max = 500) String address) {
}
