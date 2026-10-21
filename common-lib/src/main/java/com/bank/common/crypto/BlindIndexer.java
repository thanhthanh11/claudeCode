package com.bank.common.crypto;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Blind index cho cột đã mã hóa: HMAC-SHA256 (khóa riêng, hex 64 ký tự) của giá trị đã chuẩn hóa. Ciphertext
 * AES-GCM khác nhau mỗi lần nên không tìm kiếm / unique được; tra cứu bằng cột {@code *_HASH} thay thế.
 * Luôn chuẩn hóa bằng các hàm {@code normalize*} trước khi băm để "A@x.vn" và "a@x.vn " cho cùng một hash.
 */
public class BlindIndexer {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKey key;

    public BlindIndexer(SecretKey key) {
        this.key = key;
    }

    public String hash(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không tính được blind index", e);
        }
    }

    public String hashEmail(String email) {
        return hash(normalizeEmail(email));
    }

    public String hashPhone(String phone) {
        return hash(normalizePhone(phone));
    }

    /** Gắn loại giấy tờ vào hash để unique theo (loại, số) chỉ cần một cột. */
    public String hashIdNumber(String idType, String idNumber) {
        String normalized = normalizeIdNumber(idNumber);
        return normalized == null || idType == null ? null : hash(idType + ":" + normalized);
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Chỉ giữ chữ số; đầu số +84 quy về 0 (0912345678 và +84912345678 là một số). */
    public static String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        if (phone.trim().startsWith("+84") && digits.startsWith("84")) {
            digits = "0" + digits.substring(2);
        }
        return digits;
    }

    public static String normalizeIdNumber(String idNumber) {
        return idNumber == null ? null : idNumber.trim().toUpperCase(Locale.ROOT);
    }
}
