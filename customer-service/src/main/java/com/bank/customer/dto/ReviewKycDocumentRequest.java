package com.bank.customer.dto;

import com.bank.customer.enums.DocumentStatusEnum;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewKycDocumentRequest(
        @NotNull DocumentStatusEnum status,
        @Size(max = 500) String note) {
}
