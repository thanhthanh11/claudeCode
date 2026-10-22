package com.bank.interbank.dto;

import com.bank.interbank.enums.NapasResultStatusEnum;

/** Kết quả gửi lệnh / tra soát từ Napas. {@code reason} có khi bị từ chối hoặc chưa rõ kết quả. */
public record NapasResult(NapasResultStatusEnum status, String napasRef, String reason) {

    public static NapasResult pending(String reason) {
        return new NapasResult(NapasResultStatusEnum.PENDING, null, reason);
    }
}
