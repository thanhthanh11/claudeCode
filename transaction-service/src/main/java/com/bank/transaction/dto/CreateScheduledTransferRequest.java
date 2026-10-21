package com.bank.transaction.dto;

import com.bank.transaction.enums.ScheduleFrequencyEnum;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateScheduledTransferRequest(
        @NotBlank @Size(max = 20) String fromAccountNumber,
        @NotBlank @Size(max = 20) String toAccountNumber,
        @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @Size(max = 255) String description,
        @NotNull ScheduleFrequencyEnum frequency,
        @NotNull @FutureOrPresent LocalDate startDate,
        LocalDate endDate) {
}
