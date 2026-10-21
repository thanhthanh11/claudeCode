package com.bank.transaction.enums;

import java.time.LocalDate;

public enum ScheduleFrequencyEnum {
    DAILY,
    WEEKLY,
    /** Cùng ngày mỗi tháng; tháng không có ngày đó thì chạy ngày cuối tháng */
    MONTHLY;

    public LocalDate next(LocalDate date) {
        return switch (this) {
            case DAILY -> date.plusDays(1);
            case WEEKLY -> date.plusWeeks(1);
            case MONTHLY -> date.plusMonths(1);
        };
    }
}
