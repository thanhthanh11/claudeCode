package com.bank.interbank.enums;

/** Tiền đến: RECEIVED đã nhận từ Napas, chờ cộng tiền; SETTLED đã cộng; RETURNED trả lại ngân hàng gửi. */
public enum InboundStatusEnum {
    RECEIVED,
    SETTLED,
    RETURNED
}
