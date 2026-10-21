package com.bank.common.config;

import com.bank.common.crypto.EncryptionKeyring;
import com.bank.common.security.JwtProperties;
import com.bank.common.security.JwtTokenProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = CommonCryptoConfig.class)
@EnableConfigurationProperties(JwtProperties.class)
public class CommonJwtConfig {

    @Bean
    @ConditionalOnMissingBean
    public JwtTokenProvider jwtTokenProvider(JwtProperties properties, EncryptionKeyring keyring) {
        return new JwtTokenProvider(properties, keyring.jweKey());
    }
}
