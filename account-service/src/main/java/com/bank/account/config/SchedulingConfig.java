package com.bank.account.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật @Scheduled cho AccountHoldService (giải tỏa hold hết hạn) và LedgerReconciliationService.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
