package com.bank.transaction.dto;

import com.bank.transaction.enums.ScheduleFrequencyEnum;
import com.bank.transaction.enums.ScheduledTransferStatusEnum;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record ScheduledTransferResponse(
        Long id,
        String fromAccountNumber,
        String toAccountNumber,
        BigDecimal amount,
        String description,
        ScheduleFrequencyEnum frequency,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate nextRunDate,
        ScheduledTransferStatusEnum status,
        int runCount,
        LocalDate lastRunDate,
        String lastTransactionRef,
        String lastError,
        LocalDateTime createdAt) {
}
