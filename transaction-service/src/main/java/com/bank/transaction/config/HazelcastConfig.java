package com.bank.transaction.config;

import com.bank.common.util.HazelcastConfigFactory;
import com.bank.transaction.service.CustomerLookupService;
import com.bank.transaction.service.FeeScheduleService;
import com.bank.transaction.service.TransactionLimitService;
import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cache userId → customerId, biểu phí và cấu hình hạn mức. Thông tin tài khoản/số dư và số đã dùng trong hạn mức
 * luôn tra cứu trực tiếp.
 */
@Configuration
@EnableCaching
public class HazelcastConfig {

    @Bean
    public Config hazelcastInstanceConfig(@Value("${spring.application.name}") String applicationName,
                                          @Value("${bank.hazelcast.members:}") String members) {
        Config config = HazelcastConfigFactory.create(applicationName, members);
        config.addMapConfig(new MapConfig(CustomerLookupService.CUSTOMER_ID_BY_USER_CACHE).setTimeToLiveSeconds(3600));
        config.addMapConfig(new MapConfig(FeeScheduleService.FEE_SCHEDULE_CACHE).setTimeToLiveSeconds(600));
        config.addMapConfig(new MapConfig(TransactionLimitService.LIMIT_CACHE).setTimeToLiveSeconds(600));
        return config;
    }
}
