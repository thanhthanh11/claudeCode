package com.bank.common.dto;

import java.math.BigDecimal;

/**
 * Bút toán sổ cái rút gọn trả về qua Redis cho đối soát. {@code entryType}: DEBIT / CREDIT.
 * {@code internalAccount} = true với tài khoản nội bộ của ngân hàng (vd. tài khoản thu phí).
 */
public record LedgerEntryInfo(
        String accountNumber,
        String transactionRef,
        String entryType,
        BigDecimal amount,
        boolean internalAccount) {
}
