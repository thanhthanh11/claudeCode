package com.bank.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateNotificationTemplateRequest(
        @Size(max = 255) String subject,
        @NotBlank @Size(max = 4000) String body,
        @NotNull Boolean active) {
}
