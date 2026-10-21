package com.bank.transaction.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Cấu hình OTP xác nhận giao dịch ({@code bank.otp.*}). Khách hàng tự chuyển khoản từ ngưỡng
 * {@code thresholds.<currency>} trở lên phải xác nhận OTP; loại tiền không khai báo ngưỡng thì luôn cần OTP.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "bank.otp")
public class OtpConfig {

    private boolean enabled = true;
    private Duration ttl = Duration.ofMinutes(3);
    private int maxAttempts = 5;
    private int maxResends = 3;
    /** Giao dịch PENDING quá thời gian này bị hủy. */
    private Duration pendingTimeout = Duration.ofMinutes(10);
    /** Khóa HMAC-SHA256 ký OTP (Base64, tối thiểu 256 bit). BẮT BUỘC đặt OTP_HMAC_SECRET riêng ngoài môi trường dev. */
    private String hmacSecret;
    private Map<String, BigDecimal> thresholds = new HashMap<>(Map.of(
            "VND", new BigDecimal("10000000"),
            "USD", new BigDecimal("500"),
            "EUR", new BigDecimal("500")));

    public boolean isRequired(BigDecimal amount, String currency) {
        if (!enabled) {
            return false;
        }
        BigDecimal threshold = thresholds.get(currency);
        return threshold == null || amount.compareTo(threshold) >= 0;
    }
}
