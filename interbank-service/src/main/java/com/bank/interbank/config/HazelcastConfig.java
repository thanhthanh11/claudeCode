package com.bank.interbank.config;

import com.bank.common.util.HazelcastConfigFactory;
import com.bank.interbank.service.BankDirectoryService;
import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class HazelcastConfig {

    @Bean
    public Config hazelcastInstanceConfig(@Value("${spring.application.name}") String applicationName,
                                          @Value("${bank.hazelcast.members:}") String members) {
        Config config = HazelcastConfigFactory.create(applicationName, members);
        config.addMapConfig(new MapConfig(BankDirectoryService.BANK_CACHE).setTimeToLiveSeconds(3600));
        return config;
    }
}
