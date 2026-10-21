package com.bank.transaction.dto;

import java.time.LocalDateTime;

public record BeneficiaryResponse(Long id, String accountNumber, String nickname, LocalDateTime createdAt) {
}
