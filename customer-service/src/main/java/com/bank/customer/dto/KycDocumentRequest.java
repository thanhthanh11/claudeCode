package com.bank.customer.dto;

import com.bank.customer.enums.DocumentTypeEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code fileUrl}: đường dẫn file đã upload lên kho lưu trữ (S3/MinIO...).
 */
public record KycDocumentRequest(
        @NotNull DocumentTypeEnum documentType,
        @NotBlank @Size(max = 500) String fileUrl) {
}
