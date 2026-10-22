package com.bank.common.dto;

/**
 * Kết quả phân giải alias số điện thoại → tài khoản nhận. Tên chủ tài khoản đã che ({@code MaskingUtils.maskName}).
 */
public record PhoneAliasLookupResponse(
        String accountNumber,
        String currency,
        String maskedName) {
}
