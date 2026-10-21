package com.bank.transaction.service;

import com.bank.transaction.entity.Transaction;
import com.bank.transaction.enums.SagaStepEnum;
import com.bank.transaction.enums.TransactionStatusEnum;
import com.bank.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SagaTimeoutMonitorServiceTest {

    private static final int MAX_RETRIES = 3;

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private TransactionSagaService sagaService;

    private SagaTimeoutMonitorService monitorService;
    private Transaction transaction;

    @BeforeEach
    void setUp() {
        monitorService = new SagaTimeoutMonitorService(transactionRepository, sagaService, Duration.ofMinutes(2),
                MAX_RETRIES, 50);
        transaction = new Transaction();
        transaction.setTransactionRef("TX2026102000000001");
        transaction.setStatus(TransactionStatusEnum.PROCESSING);
        transaction.setSagaStep(SagaStepEnum.CREDIT_REQUESTED);
        when(transactionRepository.lockStuckBatch(any(), anyInt())).thenReturn(List.of(transaction));
    }

    @Test
    void shouldResendCommandAndCountRetry() {
        when(sagaService.resendCurrentCommand(transaction)).thenReturn(true);

        monitorService.resendStuckCommands();

        verify(sagaService).resendCurrentCommand(transaction);
        assertThat(transaction.getRetryCount()).isEqualTo(1);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatusEnum.PROCESSING);
    }

    @Test
    void shouldEscalateToManualReviewWhenRetriesExhausted() {
        transaction.setRetryCount(MAX_RETRIES);

        monitorService.resendStuckCommands();

        verify(sagaService, never()).resendCurrentCommand(any());
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatusEnum.MANUAL_REVIEW);
    }

    @Test
    void shouldEscalateWhenStepHasNoPendingCommand() {
        transaction.setSagaStep(SagaStepEnum.DEBITED);
        when(sagaService.resendCurrentCommand(transaction)).thenReturn(false);

        monitorService.resendStuckCommands();

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatusEnum.MANUAL_REVIEW);
        assertThat(transaction.getRetryCount()).isZero();
    }
}
