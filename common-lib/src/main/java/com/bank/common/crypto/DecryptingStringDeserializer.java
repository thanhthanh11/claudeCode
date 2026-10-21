package com.bank.common.crypto;

import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Giải mã value Kafka do {@link EncryptingStringSerializer} mã hóa; message cũ chưa mã hóa được đọc nguyên văn.
 * <ul>
 *   <li>Chế độ thường: không giải mã được → {@link SerializationException} (bọc trong {@code ErrorHandlingDeserializer}
 *       nên message được đẩy sang DLT thay vì chặn partition).</li>
 *   <li>Chế độ {@code lenient} (consumer DLT): trả {@value #UNDECRYPTABLE_PREFIX} + Base64 dữ liệu gốc để vẫn lưu
 *       được bản ghi DLT cho nhân viên xem.</li>
 * </ul>
 */
public class DecryptingStringDeserializer implements Deserializer<String> {

    public static final String UNDECRYPTABLE_PREFIX = "undecryptable:";

    private final AesGcmCipher cipher;
    private final boolean lenient;

    public DecryptingStringDeserializer(AesGcmCipher cipher, boolean lenient) {
        this.cipher = cipher;
        this.lenient = lenient;
    }

    @Override
    public String deserialize(String topic, byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            return cipher.decrypt(new String(data, StandardCharsets.UTF_8));
        } catch (IllegalStateException e) {
            if (lenient) {
                return UNDECRYPTABLE_PREFIX + Base64.getEncoder().encodeToString(data);
            }
            throw new SerializationException("Không giải mã được message Kafka trên topic " + topic);
        }
    }
}
