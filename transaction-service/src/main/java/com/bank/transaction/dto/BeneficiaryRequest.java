package com.bank.transaction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BeneficiaryRequest(
        @NotBlank @Size(max = 20) String accountNumber,
        @NotBlank @Size(max = 100) String nickname) {
}
