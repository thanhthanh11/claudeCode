package com.bank.account.dto;

import java.time.LocalDateTime;

/**
 * Kết quả một lần đối soát: {@code mismatches} = số sai lệch phát hiện, {@code newIssues} = số issue mới ghi nhận
 * (sai lệch đã có issue chưa xử lý thì không ghi lại).
 */
public record ReconciliationRunResponse(LocalDateTime checkedAt, int mismatches, int newIssues) {
}
