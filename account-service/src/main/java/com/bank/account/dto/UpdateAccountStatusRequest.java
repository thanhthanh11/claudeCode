package com.bank.account.dto;

import com.bank.account.enums.AccountStatusEnum;
import jakarta.validation.constraints.NotNull;

public record UpdateAccountStatusRequest(@NotNull AccountStatusEnum status) {
}
