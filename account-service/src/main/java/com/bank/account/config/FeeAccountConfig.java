package com.bank.account.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tài khoản nội bộ nhận phí giao dịch theo loại tiền:
 * {@code bank.fee.accounts.VND=990000000001,990000000004}. Nhiều tài khoản cho một loại tiền để chia tải khóa dòng
 * (mỗi lệnh có phí khóa tài khoản thu phí bằng SELECT FOR UPDATE).
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "bank.fee")
public class FeeAccountConfig {

    private Map<String, List<String>> accounts = new HashMap<>();

    /**
     * Tài khoản thu phí cho giao dịch: chọn cố định theo hash {@code transactionRef} để lệnh gửi lại của cùng giao
     * dịch luôn vào cùng tài khoản. Trả về null nếu loại tiền chưa cấu hình.
     */
    public String accountFor(String currency, String transactionRef) {
        List<String> candidates = accountsFor(currency);
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(Math.floorMod(transactionRef.hashCode(), candidates.size()));
    }

    public List<String> accountsFor(String currency) {
        return accounts.getOrDefault(currency, List.of());
    }
}
