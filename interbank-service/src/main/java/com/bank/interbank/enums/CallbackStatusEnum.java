package com.bank.interbank.enums;

/** Báo kết quả tiền đến cho Napas: PENDING chờ gửi / gửi lại; DONE đã báo; FAILED quá số lần, xử lý thủ công. */
public enum CallbackStatusEnum {
    PENDING,
    DONE,
    FAILED
}
