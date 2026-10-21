package com.bank.account.dto;

import com.bank.common.event.AccountResultPayload;

/**
 * Kết quả xử lý một lệnh số dư: loại event trả về (ACCOUNT_DEBITED, ACCOUNT_DEBIT_FAILED, ...) và payload.
 */
public record BalanceOperationResponse(String eventType, AccountResultPayload payload) {
}
