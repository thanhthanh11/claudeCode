package com.bank.common.crypto;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Khóa AES gốc ({@code bank.security.encryption.key}, env {@code AES_ENCRYPTION_KEY}): Base64, đúng 256 bit.
 * Mọi khóa con (JWE, cột DB, Kafka, Redis, blind index) dẫn xuất từ khóa này bằng HKDF — xem {@link EncryptionKeyring}.
 * BẮT BUỘC đặt khóa riêng ngoài môi trường dev; đổi khóa phải mã hóa lại toàn bộ dữ liệu đã lưu.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "bank.security.encryption")
public class EncryptionProperties {

    private String key;
}
