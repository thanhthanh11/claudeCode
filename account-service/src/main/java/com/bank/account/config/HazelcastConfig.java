package com.bank.account.config;

import com.bank.account.service.CustomerLookupService;
import com.bank.common.util.HazelcastConfigFactory;
import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chỉ cache dữ liệu ít thay đổi (userId → customerId). Số dư/tài khoản luôn đọc từ DB.
 */
@Configuration
@EnableCaching
public class HazelcastConfig {

    @Bean
    public Config hazelcastInstanceConfig(@Value("${spring.application.name}") String applicationName,
                                          @Value("${bank.hazelcast.members:}") String members) {
        Config config = HazelcastConfigFactory.create(applicationName, members);
        config.addMapConfig(new MapConfig(CustomerLookupService.CUSTOMER_ID_BY_USER_CACHE).setTimeToLiveSeconds(3600));
        return config;
    }
}
