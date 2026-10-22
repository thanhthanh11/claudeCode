package com.bank.interbank.dto;

import com.bank.interbank.enums.InboundStatusEnum;

/** Phản hồi webhook: RECEIVED (đang cộng tiền, kết quả báo sau qua callback) hoặc RETURNED ngay khi kiểm tra sớm. */
public record InboundTransferResponse(String napasRef, InboundStatusEnum status, String reason) {
}
