package com.bank.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Mã hóa chuỗi bằng AES-256-GCM (IV 12 byte ngẫu nhiên mỗi lần, tag 128 bit — vừa bí mật vừa chống sửa đổi).
 * Định dạng: {@code enc:v1:} + Base64(IV ‖ ciphertext ‖ tag).
 * <p>
 * Giá trị không có tiền tố {@value #PREFIX} được trả nguyên văn khi giải mã — dữ liệu cũ trước khi bật mã hóa vẫn
 * đọc được trong lúc chuyển đổi (backfill). Thread-safe ({@link Cipher} tạo mới mỗi lần).
 */
public class AesGcmCipher {

    public static final String PREFIX = "enc:v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public AesGcmCipher(SecretKey key) {
        this.key = key;
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        return PREFIX + Base64.getEncoder().encodeToString(encryptBytes(plaintext.getBytes(StandardCharsets.UTF_8)));
    }

    public String decrypt(String value) {
        if (!isEncrypted(value)) {
            return value;
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(value.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Dữ liệu mã hóa sai định dạng");
        }
        return new String(decryptBytes(data), StandardCharsets.UTF_8);
    }

    /** IV ‖ ciphertext ‖ tag. */
    public byte[] encryptBytes(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(IV_LENGTH + encrypted.length).put(iv).put(encrypted).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không mã hóa được dữ liệu", e);
        }
    }

    /** Không đưa dữ liệu hay nguyên nhân chi tiết vào message (có thể bị log / lưu DB). */
    public byte[] decryptBytes(byte[] data) {
        if (data.length < IV_LENGTH + TAG_BITS / 8) {
            throw new IllegalStateException("Dữ liệu mã hóa sai định dạng");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_LENGTH));
            return cipher.doFinal(data, IV_LENGTH, data.length - IV_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không giải mã được dữ liệu (sai khóa hoặc dữ liệu bị sửa)");
        }
    }
}
