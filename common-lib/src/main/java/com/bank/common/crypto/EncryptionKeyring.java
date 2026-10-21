package com.bank.common.crypto;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Bộ khóa dẫn xuất từ một khóa AES gốc bằng HKDF-SHA256 (RFC 5869), mỗi mục đích một khóa con 256 bit: lộ khóa
 * con của một kênh không làm lộ khóa gốc hay khóa của kênh khác. Thiếu khóa gốc / không phải Base64 / không đủ
 * 256 bit → service không khởi động.
 */
public class EncryptionKeyring {

    static final String PURPOSE_JWE = "bank/jwe";
    static final String PURPOSE_DB = "bank/db-column";
    static final String PURPOSE_KAFKA = "bank/kafka";
    static final String PURPOSE_REDIS = "bank/redis-rr";
    static final String PURPOSE_BLIND_INDEX = "bank/blind-index";
    private static final int KEY_BYTES = 32;
    private static final String HMAC = "HmacSHA256";

    private final SecretKey jweKey;
    private final AesGcmCipher dbCipher;
    private final AesGcmCipher kafkaCipher;
    private final AesGcmCipher redisCipher;
    private final BlindIndexer blindIndexer;

    public EncryptionKeyring(String base64MasterKey) {
        byte[] master = decodeMasterKey(base64MasterKey);
        this.jweKey = derive(master, PURPOSE_JWE);
        this.dbCipher = new AesGcmCipher(derive(master, PURPOSE_DB));
        this.kafkaCipher = new AesGcmCipher(derive(master, PURPOSE_KAFKA));
        this.redisCipher = new AesGcmCipher(derive(master, PURPOSE_REDIS));
        this.blindIndexer = new BlindIndexer(new SecretKeySpec(hkdf(master, PURPOSE_BLIND_INDEX), HMAC));
    }

    /** Khóa AES-256 cho JWE (A256GCM, direct). */
    public SecretKey jweKey() {
        return jweKey;
    }

    /** Mã hóa cột PII trong DB ({@link EncryptedStringConverter}). */
    public AesGcmCipher db() {
        return dbCipher;
    }

    /** Mã hóa value message Kafka. */
    public AesGcmCipher kafka() {
        return kafkaCipher;
    }

    /** Mã hóa payload / reply trên kênh Redis request-reply. */
    public AesGcmCipher redis() {
        return redisCipher;
    }

    public BlindIndexer blindIndexer() {
        return blindIndexer;
    }

    private static SecretKey derive(byte[] master, String purpose) {
        return new SecretKeySpec(hkdf(master, purpose), "AES");
    }

    /** HKDF-SHA256 extract (salt = 0^32) + expand một block (32 byte). */
    static byte[] hkdf(byte[] inputKey, String info) {
        try {
            Mac extract = Mac.getInstance(HMAC);
            extract.init(new SecretKeySpec(new byte[KEY_BYTES], HMAC));
            byte[] prk = extract.doFinal(inputKey);
            Mac expand = Mac.getInstance(HMAC);
            expand.init(new SecretKeySpec(prk, HMAC));
            expand.update(info.getBytes(StandardCharsets.UTF_8));
            expand.update((byte) 1);
            return expand.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không dẫn xuất được khóa", e);
        }
    }

    private static byte[] decodeMasterKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(
                    "Thiếu cấu hình bank.security.encryption.key (biến môi trường AES_ENCRYPTION_KEY)");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("bank.security.encryption.key phải là chuỗi Base64");
        }
        if (key.length != KEY_BYTES) {
            throw new IllegalStateException("bank.security.encryption.key phải dài đúng 256 bit (32 byte)");
        }
        return key;
    }
}
