package com.bank.customer.dto;

import com.bank.customer.enums.DocumentStatusEnum;
import com.bank.customer.enums.DocumentTypeEnum;

import java.time.LocalDateTime;

public record KycDocumentResponse(
        Long id,
        Long customerId,
        DocumentTypeEnum documentType,
        String fileUrl,
        DocumentStatusEnum status,
        String reviewedBy,
        LocalDateTime reviewedAt,
        String note,
        LocalDateTime createdAt) {
}
