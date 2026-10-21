package com.bank.notification.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật @Scheduled cho NotificationRetryService.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
