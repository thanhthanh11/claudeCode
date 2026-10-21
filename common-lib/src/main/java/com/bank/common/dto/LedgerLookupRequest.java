package com.bank.common.dto;

/**
 * Tra cứu các bút toán sổ cái của một giao dịch (đối soát).
 */
public record LedgerLookupRequest(String transactionRef) {
}
