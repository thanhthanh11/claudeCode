package com.bank.common.dto;

import java.io.Serializable;

/**
 * Thông tin khách hàng rút gọn dùng giữa các service (Redis request-reply, REST nội bộ).
 */
public record CustomerInfoResponse(
        Long id,
        Long userId,
        String customerCode,
        String fullName,
        String phone,
        String email,
        String status,
        String kycStatus) implements Serializable {

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public boolean isKycVerified() {
        return "VERIFIED".equals(kycStatus);
    }
}
