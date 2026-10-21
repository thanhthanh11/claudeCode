package com.bank.transaction.service;

import com.bank.common.dto.LedgerEntryInfo;
import com.bank.transaction.dto.FeeScheduleResponse;
import com.bank.transaction.entity.Transaction;
import com.bank.transaction.enums.SagaStepEnum;
import com.bank.transaction.enums.TransactionStatusEnum;
import com.bank.transaction.enums.TransactionTypeEnum;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeeAndReconciliationTest {

    private static final FeeScheduleResponse VND_TRANSFER = new FeeScheduleResponse(1L, TransactionTypeEnum.TRANSFER,
            "VND", BigDecimal.ZERO, new BigDecimal("0.0002"), new BigDecimal("2000"), new BigDecimal("20000"), true);

    @Test
    void feeShouldBeClampedToMinAndMax() {
        assertThat(VND_TRANSFER.calculateFee(new BigDecimal("100000"), "VND")).isEqualByComparingTo("2000");
        assertThat(VND_TRANSFER.calculateFee(new BigDecimal("50000000"), "VND")).isEqualByComparingTo("10000");
        assertThat(VND_TRANSFER.calculateFee(new BigDecimal("500000000"), "VND")).isEqualByComparingTo("20000");
    }

    @Test
    void feeShouldBeRoundedByCurrencyFractionDigits() {
        FeeScheduleResponse usd = new FeeScheduleResponse(2L, TransactionTypeEnum.TRANSFER, "USD", BigDecimal.ZERO,
                new BigDecimal("0.001"), BigDecimal.ZERO, null, true);
        assertThat(usd.calculateFee(new BigDecimal("123.45"), "USD")).isEqualByComparingTo("0.12");
        assertThat(VND_TRANSFER.calculateFee(new BigDecimal("12345678"), "VND").scale()).isZero();
    }

    @Test
    void inactiveScheduleShouldChargeNothing() {
        assertThat(FeeScheduleResponse.NONE.calculateFee(new BigDecimal("1000000"), "VND")).isEqualByComparingTo("0");
    }

    @Test
    void completedTransferWithFeeShouldMatchLedger() {
        Transaction transaction = transaction(TransactionTypeEnum.TRANSFER, TransactionStatusEnum.COMPLETED, "2000");
        LedgerEntryInfo[] ledger = {
                new LedgerEntryInfo("A", "TX1", "DEBIT", new BigDecimal("102000.0000"), false),
                new LedgerEntryInfo("990000000001", "TX1", "CREDIT", new BigDecimal("2000"), true),
                new LedgerEntryInfo("B", "TX1", "CREDIT", new BigDecimal("100000"), false)};

        assertThat(TransactionReconciliationService.sameEntries(
                TransactionReconciliationService.expectedEntries(transaction),
                TransactionReconciliationService.actualEntries(ledger))).isTrue();
    }

    @Test
    void missingCreditShouldBeMismatch() {
        Transaction transaction = transaction(TransactionTypeEnum.TRANSFER, TransactionStatusEnum.COMPLETED, "0");
        LedgerEntryInfo[] ledger = {new LedgerEntryInfo("A", "TX1", "DEBIT", new BigDecimal("100000"), false)};

        assertThat(TransactionReconciliationService.sameEntries(
                TransactionReconciliationService.expectedEntries(transaction),
                TransactionReconciliationService.actualEntries(ledger))).isFalse();
    }

    @Test
    void compensatedTransferShouldExpectRefundOfAmountAndFee() {
        Transaction transaction = transaction(TransactionTypeEnum.TRANSFER, TransactionStatusEnum.COMPENSATED, "2000");

        assertThat(TransactionReconciliationService.expectedEntries(transaction)).containsExactlyInAnyOrder(
                "A|DEBIT|102000", "A|CREDIT|102000", "<FEE>|CREDIT|2000", "<FEE>|DEBIT|2000");
    }

    @Test
    void failedTransactionShouldHaveNoLedgerEntries() {
        Transaction transaction = transaction(TransactionTypeEnum.TRANSFER, TransactionStatusEnum.FAILED, "2000");
        transaction.setSagaStep(SagaStepEnum.DEBIT_REQUESTED);

        assertThat(TransactionReconciliationService.expectedEntries(transaction)).isEqualTo(List.of());
    }

    private static Transaction transaction(TransactionTypeEnum type, TransactionStatusEnum status, String fee) {
        Transaction transaction = new Transaction();
        transaction.setTransactionRef("TX1");
        transaction.setTransactionType(type);
        transaction.setStatus(status);
        transaction.setFromAccountNumber("A");
        transaction.setToAccountNumber("B");
        transaction.setAmount(new BigDecimal("100000"));
        transaction.setFee(new BigDecimal(fee));
        return transaction;
    }
}
