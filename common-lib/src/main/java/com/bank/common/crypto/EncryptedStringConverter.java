package com.bank.common.crypto;

import jakarta.persistence.AttributeConverter;

/**
 * Mã hóa cột PII (AES-256-GCM, khóa con {@code db}) trong suốt với code nghiệp vụ:
 * {@code @Convert(converter = EncryptedStringConverter.class)}. Hibernate lấy converter qua Spring
 * (SpringBeanContainer của Spring Boot) nên {@link EncryptionKeyring} được inject qua constructor.
 * <p>
 * Cột đã mã hóa không tìm kiếm / unique được — dùng thêm cột {@code *_HASH} ({@link BlindIndexer}).
 * Giá trị cũ chưa mã hóa vẫn đọc được (xem {@link AesGcmCipher#decrypt(String)}).
 */
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final AesGcmCipher cipher;

    public EncryptedStringConverter(EncryptionKeyring keyring) {
        this.cipher = keyring.db();
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return cipher.decrypt(dbData);
    }
}
