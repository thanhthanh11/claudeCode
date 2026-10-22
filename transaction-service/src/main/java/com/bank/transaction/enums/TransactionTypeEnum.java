package com.bank.transaction.enums;

public enum TransactionTypeEnum {
    TRANSFER,
    DEPOSIT,
    WITHDRAWAL,
    /** Chuyển tiền ra ngân hàng khác qua Napas: trừ tiền tài khoản nguồn rồi gửi lệnh sang interbank-service. */
    INTERBANK_OUT,
    /** Tiền từ ngân hàng khác chuyển đến (Napas gọi vào interbank-service): cộng tiền tài khoản nhận. */
    INTERBANK_IN
}
