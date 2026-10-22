package com.bank.common.dto;

/**
 * Tên chủ tài khoản ở ngân hàng khác (tra qua Napas). {@code accountName} là tên đầy đủ do Napas trả về.
 */
public record NameInquiryResponse(
        String bankCode,
        String bankName,
        String accountNumber,
        String accountName) {
}
