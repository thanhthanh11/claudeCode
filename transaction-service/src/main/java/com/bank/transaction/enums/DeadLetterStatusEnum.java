package com.bank.transaction.enums;

public enum DeadLetterStatusEnum {
    NEW,
    /** Đã đưa lại vào topic gốc qua outbox */
    REPLAYED,
    /** Nhân viên xác nhận bỏ qua (đã xử lý thủ công / message không còn giá trị) */
    IGNORED
}
