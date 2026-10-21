package com.bank.common.config;

import com.bank.common.crypto.EncryptionKeyring;
import com.bank.common.crypto.EncryptionProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Bộ khóa AES dùng chung (JWE, cột DB, Kafka, Redis, blind index). Không điều kiện servlet: gateway (WebFlux) cũng
 * cần khóa JWE để verify token.
 */
@AutoConfiguration(before = CommonJwtConfig.class)
@EnableConfigurationProperties(EncryptionProperties.class)
public class CommonCryptoConfig {

    @Bean
    @ConditionalOnMissingBean
    public EncryptionKeyring encryptionKeyring(EncryptionProperties properties) {
        return new EncryptionKeyring(properties.getKey());
    }
}
