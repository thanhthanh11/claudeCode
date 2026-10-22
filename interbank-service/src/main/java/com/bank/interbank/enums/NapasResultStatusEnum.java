package com.bank.interbank.enums;

/**
 * Kết quả Napas trả về: ACCEPTED (đã ghi có cho ngân hàng nhận), REJECTED (từ chối — tiền chưa rời ngân hàng),
 * PENDING (đang xử lý / không rõ — phải tra soát, không được coi là thất bại), NOT_FOUND (tra soát: Napas chưa
 * nhận lệnh — an toàn để gửi lại).
 */
public enum NapasResultStatusEnum {
    ACCEPTED,
    REJECTED,
    PENDING,
    NOT_FOUND
}
