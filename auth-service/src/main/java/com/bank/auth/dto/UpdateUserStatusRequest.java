package com.bank.auth.dto;

import com.bank.auth.enums.UserStatusEnum;
import jakarta.validation.constraints.NotNull;

public record UpdateUserStatusRequest(@NotNull UserStatusEnum status) {
}
