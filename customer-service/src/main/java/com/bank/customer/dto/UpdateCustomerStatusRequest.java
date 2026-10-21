package com.bank.customer.dto;

import com.bank.customer.enums.CustomerStatusEnum;
import jakarta.validation.constraints.NotNull;

public record UpdateCustomerStatusRequest(@NotNull CustomerStatusEnum status) {
}
