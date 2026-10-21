package com.bank.common.crypto;

import org.apache.kafka.common.serialization.Serializer;

import java.nio.charset.StandardCharsets;

/**
 * Value Kafka (envelope JSON) được mã hóa AES-256-GCM (khóa con {@code kafka}) trước khi gửi: broker, công cụ đọc
 * topic và bản sao log của Kafka không thấy số tài khoản / số tiền. Gắn vào producer factory bởi
 * {@code CommonKafkaConfig} (bean Spring — khóa không nằm trong cấu hình Kafka client nên không bị in ra log).
 */
public class EncryptingStringSerializer implements Serializer<String> {

    private final AesGcmCipher cipher;

    public EncryptingStringSerializer(AesGcmCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public byte[] serialize(String topic, String data) {
        return data == null ? null : cipher.encrypt(data).getBytes(StandardCharsets.UTF_8);
    }
}
