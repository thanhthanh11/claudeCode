package com.bank.customer.dto;

import com.bank.customer.enums.CustomerStatusEnum;
import com.bank.customer.enums.GenderEnum;
import com.bank.customer.enums.IdTypeEnum;
import com.bank.customer.enums.KycStatusEnum;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record CustomerResponse(
        Long id,
        Long userId,
        String customerCode,
        String fullName,
        LocalDate dateOfBirth,
        GenderEnum gender,
        IdTypeEnum idType,
        String idNumber,
        String phone,
        String email,
        String address,
        KycStatusEnum kycStatus,
        CustomerStatusEnum status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
