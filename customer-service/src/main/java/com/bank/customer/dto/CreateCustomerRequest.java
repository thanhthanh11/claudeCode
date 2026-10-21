package com.bank.customer.dto;

import com.bank.customer.enums.GenderEnum;
import com.bank.customer.enums.IdTypeEnum;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * {@code userId} chỉ được nhân viên (CUSTOMER_MANAGE) truyền vào; khách hàng tự tạo hồ sơ
 * thì hệ thống lấy userId từ token.
 */
public record CreateCustomerRequest(
        Long userId,
        @NotBlank @Size(max = 150) String fullName,
        @NotNull @Past LocalDate dateOfBirth,
        GenderEnum gender,
        @NotNull IdTypeEnum idType,
        @NotBlank @Pattern(regexp = "^[A-Z0-9]{8,20}$", message = "Số giấy tờ 8-20 ký tự chữ in hoa/số") String idNumber,
        @NotBlank @Pattern(regexp = "^(\\+84|0)\\d{9,10}$", message = "Số điện thoại không hợp lệ") String phone,
        @Email @Size(max = 150) String email,
        @Size(max = 500) String address) {
}
