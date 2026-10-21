package com.bank.customer.dto;

import com.bank.customer.enums.KycStatusEnum;
import jakarta.validation.constraints.NotNull;

public record UpdateKycStatusRequest(@NotNull KycStatusEnum kycStatus) {
}
