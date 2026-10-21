package com.bank.transaction.dto;

import com.bank.transaction.enums.TransactionTypeEnum;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Biểu phí (cache trong Hazelcast nên Serializable). {@link #NONE} = không thu phí.
 */
public record FeeScheduleResponse(
        Long id,
        TransactionTypeEnum transactionType,
        String currency,
        BigDecimal fixedFee,
        BigDecimal percentRate,
        BigDecimal minFee,
        BigDecimal maxFee,
        Boolean active) implements Serializable {

    public static final FeeScheduleResponse NONE = new FeeScheduleResponse(null, null, null, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, null, false);

    /** phí = fixed + amount × rate, chặn trong [min, max], làm tròn theo số lẻ của loại tiền (VND: 0, USD: 2). */
    public BigDecimal calculateFee(BigDecimal amount, String currencyCode) {
        if (!Boolean.TRUE.equals(active)) {
            return BigDecimal.ZERO;
        }
        BigDecimal fee = fixedFee.add(amount.multiply(percentRate));
        if (fee.compareTo(minFee) < 0) {
            fee = minFee;
        }
        if (maxFee != null && fee.compareTo(maxFee) > 0) {
            fee = maxFee;
        }
        return fee.setScale(Currency.getInstance(currencyCode).getDefaultFractionDigits(), RoundingMode.HALF_UP);
    }
}
