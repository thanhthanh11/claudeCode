package com.bank.transaction.service;

import com.bank.common.audit.AuditPublisher;
import com.bank.common.exception.BadRequestException;
import com.bank.transaction.dto.TransactionLimitResponse;
import com.bank.transaction.enums.TransactionTypeEnum;
import com.bank.transaction.repository.TransactionLimitRepository;
import com.bank.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TransactionLimitServiceTest {

    private static final String ACCOUNT = "100000000001";
    private static final TransactionLimitResponse LIMIT = new TransactionLimitResponse(1L, TransactionTypeEnum.TRANSFER,
            null, "VND", new BigDecimal("500000000"), new BigDecimal("1000000000"), 3);

    @Mock
    private TransactionLimitRepository limitRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private CacheManager cacheManager;
    @Mock
    private AuditPublisher auditPublisher;
    @InjectMocks
    private TransactionLimitService limitService;

    @BeforeEach
    void setUp() {
        usage("0", 0);
    }

    @Test
    void amountAbovePerTransactionLimitShouldBeRejectedBeforeLocking() {
        assertCode(() -> limitService.checkAndLock(LIMIT, TransactionTypeEnum.TRANSFER, ACCOUNT,
                new BigDecimal("500000001")), "LIMIT_PER_TRANSACTION_EXCEEDED");
        verify(limitRepository, never()).lockAccount(anyString());
    }

    @Test
    void dailyAmountLimitShouldIncludeUsageOfTheDay() {
        usage("900000000", 1);

        assertCode(() -> limitService.checkAndLock(LIMIT, TransactionTypeEnum.TRANSFER, ACCOUNT,
                new BigDecimal("100000001")), "LIMIT_DAILY_AMOUNT_EXCEEDED");
        verify(limitRepository).lockAccount(ACCOUNT);
    }

    @Test
    void dailyCountLimitShouldBeEnforced() {
        usage("1000", 3);

        assertCode(() -> limitService.checkAndLock(LIMIT, TransactionTypeEnum.TRANSFER, ACCOUNT, BigDecimal.TEN),
                "LIMIT_DAILY_COUNT_EXCEEDED");
    }

    @Test
    void transactionWithinLimitsShouldPassAndHoldAccountLock() {
        // Đã dùng 600 triệu + 400 triệu = đúng bằng hạn mức ngày 1 tỷ, giao dịch thứ 3 = đúng bằng số lần tối đa
        usage("600000000", 2);

        assertThatCode(() -> limitService.checkAndLock(LIMIT, TransactionTypeEnum.TRANSFER, ACCOUNT,
                new BigDecimal("400000000"))).doesNotThrowAnyException();
        verify(limitRepository).insertLockRowIfAbsent(ACCOUNT);
        verify(limitRepository).lockAccount(ACCOUNT);
    }

    @Test
    void depositShouldCountIncomingTransactions() {
        TransactionLimitResponse depositLimit = new TransactionLimitResponse(2L, TransactionTypeEnum.DEPOSIT, null,
                "VND", null, new BigDecimal("1000"), null);

        limitService.checkAndLock(depositLimit, TransactionTypeEnum.DEPOSIT, ACCOUNT, BigDecimal.ONE);

        verify(transactionRepository).sumIncomingSince(eq(ACCOUNT), eq("DEPOSIT"), any());
        verify(transactionRepository, never()).sumOutgoingSince(any(), any(), any());
    }

    @Test
    void unlimitedShouldSkipEverything() {
        limitService.checkAndLock(TransactionLimitResponse.NONE, TransactionTypeEnum.TRANSFER, ACCOUNT,
                new BigDecimal("999999999999"));

        verify(limitRepository, never()).lockAccount(anyString());
    }

    private void usage(String amount, long count) {
        List<Object[]> rows = List.<Object[]>of(new Object[]{new BigDecimal(amount), count});
        lenient().when(transactionRepository.sumOutgoingSince(anyString(), anyString(), any())).thenReturn(rows);
        lenient().when(transactionRepository.sumIncomingSince(anyString(), anyString(), any())).thenReturn(rows);
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BadRequestException.class,
                e -> org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo(code));
    }
}
