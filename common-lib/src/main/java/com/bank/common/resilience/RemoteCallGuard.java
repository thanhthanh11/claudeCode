package com.bank.common.resilience;

import java.util.function.Supplier;

/**
 * Bảo vệ lời gọi sang service khác bằng circuit breaker: khi service đích lỗi/timeout liên tục, mạch mở và
 * các lời gọi tiếp theo bị từ chối ngay ({@code ServiceUnavailableException} 503) thay vì chờ timeout.
 * Lỗi nghiệp vụ (4xx) không bị tính là lỗi.
 */
public interface RemoteCallGuard {

    RemoteCallGuard NOOP = new RemoteCallGuard() {
        @Override
        public <T> T call(String target, Supplier<T> action) {
            return action.get();
        }
    };

    <T> T call(String target, Supplier<T> action);
}
