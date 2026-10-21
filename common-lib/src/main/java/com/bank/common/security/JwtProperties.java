package com.bank.common.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Cấu hình JWT ({@code bank.security.jwt.*}). Khóa mã hóa token (JWE) dẫn xuất từ khóa AES gốc
 * ({@code bank.security.encryption.key}) — xem {@link com.bank.common.crypto.EncryptionKeyring}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "bank.security.jwt")
public class JwtProperties {

    private String issuer = "bank-auth-service";
    private Duration accessTokenTtl = Duration.ofMinutes(15);
    private Duration refreshTokenTtl = Duration.ofDays(7);
    private Duration serviceTokenTtl = Duration.ofMinutes(1);
}
