package com.bank.common.dto;

/**
 * Tra cứu khách hàng theo {@code customerId} hoặc {@code userId} (một trong hai).
 */
public record CustomerLookupRequest(Long customerId, Long userId) {

    public static CustomerLookupRequest byId(Long customerId) {
        return new CustomerLookupRequest(customerId, null);
    }

    public static CustomerLookupRequest byUserId(Long userId) {
        return new CustomerLookupRequest(null, userId);
    }
}
