package com.bank.interbank.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật @Scheduled: OutboundTransferService (gửi / tra soát Napas), InterbankEventPublisherService (publish kết quả),
 * InboundCallbackService (báo Napas kết quả tiền đến).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
