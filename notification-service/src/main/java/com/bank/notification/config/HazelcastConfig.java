package com.bank.notification.config;

import com.bank.common.util.HazelcastConfigFactory;
import com.bank.notification.service.CustomerLookupService;
import com.bank.notification.service.NotificationTemplateService;
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
        config.addMapConfig(new MapConfig(NotificationTemplateService.TEMPLATE_CACHE).setTimeToLiveSeconds(3600));
        config.addMapConfig(new MapConfig(CustomerLookupService.CUSTOMER_CONTACT_CACHE).setTimeToLiveSeconds(300));
        config.addMapConfig(new MapConfig(CustomerLookupService.CUSTOMER_ID_BY_USER_CACHE).setTimeToLiveSeconds(3600));
        return config;
    }
}
