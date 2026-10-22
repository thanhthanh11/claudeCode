package com.bank.common.util;

/**
 * Che dữ liệu nhạy cảm trước khi ghi log.
 */
public final class MaskingUtils {

    private MaskingUtils() {
    }

    /** 0912345678 → ******5678 */
    public static String maskTail(String value, int visible) {
        if (value == null || value.length() <= visible) {
            return "****";
        }
        return "*".repeat(value.length() - visible) + value.substring(value.length() - visible);
    }

    public static String maskAccountNumber(String accountNumber) {
        return maskTail(accountNumber, 4);
    }

    public static String maskPhone(String phone) {
        return maskTail(phone, 3);
    }

    /** nguyenvana@gmail.com → n*********@gmail.com */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "****";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "*" + email.substring(at);
        }
        return email.charAt(0) + "*".repeat(at - 1) + email.substring(at);
    }

    /** Hiển thị tên người nhận để xác nhận mà không lộ đủ tên: "Nguyễn Văn An" → "NGUYỄN V** A*". */
    public static String maskName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "****";
        }
        String[] words = fullName.trim().toUpperCase(java.util.Locale.ROOT).split("\\s+");
        StringBuilder masked = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (i > 0) {
                masked.append(' ');
            }
            masked.append(i == 0 ? word : word.charAt(0) + "*".repeat(Math.max(word.length() - 1, 1)));
        }
        return masked.toString();
    }

    public static String maskRecipient(String recipient) {
        return recipient != null && recipient.contains("@") ? maskEmail(recipient) : maskPhone(recipient);
    }
}
