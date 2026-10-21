package com.bank.transaction.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật @Scheduled: outbox, giao dịch Saga bị kẹt, giao dịch chờ OTP hết hạn, đối soát, chuyển tiền định kỳ.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
