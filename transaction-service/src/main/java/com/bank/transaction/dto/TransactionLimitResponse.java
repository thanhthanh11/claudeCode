package com.bank.transaction.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.bank.transaction.enums.TransactionTypeEnum;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Hạn mức (cache trong Hazelcast nên Serializable). Giá trị null = không giới hạn; {@link #NONE} = không có hạn mức.
 */
public record TransactionLimitResponse(
        Long id,
        TransactionTypeEnum transactionType,
        String accountType,
        String currency,
        BigDecimal perTxnMax,
        BigDecimal dailyMax,
        Integer dailyCountMax) implements Serializable {

    public static final TransactionLimitResponse NONE = new TransactionLimitResponse(null, null, null, null, null,
            null, null);

    @JsonIgnore
    public boolean isUnlimited() {
        return perTxnMax == null && dailyMax == null && dailyCountMax == null;
    }
}
