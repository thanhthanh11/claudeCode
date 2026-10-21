package com.bank.transaction.dto;

import java.time.LocalDateTime;

public record ReconciliationRunResponse(
        LocalDateTime from,
        LocalDateTime to,
        int checked,
        int mismatches,
        int newIssues) {
}
