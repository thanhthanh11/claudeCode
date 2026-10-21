package com.bank.account.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Sao kê tài khoản trong khoảng [fromDate, toDate] (theo ngày, gồm cả hai đầu).
 */
public record StatementResponse(
        String accountNumber,
        String currency,
        LocalDate fromDate,
        LocalDate toDate,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        List<LedgerEntryResponse> entries) {
}
