package com.bank.transaction.service;

import com.bank.common.event.AccountCommandPayload;
import com.bank.common.event.AccountResultPayload;
import com.bank.common.event.EventEnvelope;
import com.bank.common.event.EventTypes;
import com.bank.common.event.KafkaTopics;
import com.bank.transaction.entity.Transaction;
import com.bank.transaction.enums.SagaStepEnum;
import com.bank.transaction.enums.TransactionStatusEnum;
import com.bank.transaction.enums.TransactionTypeEnum;
import com.bank.transaction.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionSagaServiceTest {

    private static final String REF = "TX2026102000000001";

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private OutboxService outboxService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private TransactionSagaService sagaService;
    private Transaction transaction;

    @BeforeEach
    void setUp() {
        sagaService = new TransactionSagaService(transactionRepository, outboxService, objectMapper);
        transaction = new Transaction();
        transaction.setTransactionRef(REF);
        transaction.setTransactionType(TransactionTypeEnum.TRANSFER);
        transaction.setFromAccountNumber("100000000001");
        transaction.setToAccountNumber("100000000002");
        transaction.setAmount(new BigDecimal("500000"));
        transaction.setCurrency("VND");
        transaction.setStatus(TransactionStatusEnum.PROCESSING);
        transaction.setSagaStep(SagaStepEnum.DEBIT_REQUESTED);
        lenient().when(transactionRepository.findByTransactionRefForUpdate(REF)).thenReturn(Optional.of(transaction));
    }

    @Test
    void debitedTransferShouldRequestCredit() {
        sagaService.handle(event(EventTypes.ACCOUNT_DEBITED, null));

        assertThat(transaction.getSagaStep()).isEqualTo(SagaStepEnum.CREDIT_REQUESTED);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatusEnum.PROCESSING);
        ArgumentCaptor<AccountCommandPayload> command = ArgumentCaptor.forClass(AccountCommandPayload.class);
        verify(outboxService).enqueue(eq(KafkaTopics.ACCOUNT_COMMANDS), eq(REF),
                eq(EventTypes.ACCOUNT_CREDIT_REQUESTED), command.capture());
        assertThat(command.getValue().accountNumber()).isEqualTo("100000000002");
    }

    @Test
    void debitFailedShouldFailTransaction() {
        sagaService.handle(event(EventTypes.ACCOUNT_DEBIT_FAILED, "Số dư khả dụng không đủ"));

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatusEnum.FAILED);
        assertThat(transaction.getFailureReason()).isEqualTo("Số dư khả dụng không đủ");
        verify(outboxService).enqueue(eq(KafkaTopics.NOTIFICATION_EVENTS), eq(REF),
                eq(EventTypes.TRANSACTION_FAILED), any());
    }

    @Test
    void creditFailedShouldRequestRefund() {
        transaction.setSagaStep(SagaStepEnum.CREDIT_REQUESTED);

        sagaService.handle(event(EventTypes.ACCOUNT_CREDIT_FAILED, "Tài khoản nhận không ở trạng thái hoạt động"));

        assertThat(transaction.getSagaStep()).isEqualTo(SagaStepEnum.COMPENSATION_REQUESTED);
        ArgumentCaptor<AccountCommandPayload> command = ArgumentCaptor.forClass(AccountCommandPayload.class);
        verify(outboxService).enqueue(eq(KafkaTopics.ACCOUNT_COMMANDS), eq(REF),
                eq(EventTypes.ACCOUNT_REFUND_REQUESTED), command.capture());
        assertThat(command.getValue().accountNumber()).isEqualTo("100000000001");
    }

    @Test
    void duplicateCreditedEventShouldBeIgnored() {
        transaction.setStatus(TransactionStatusEnum.COMPLETED);
        transaction.setSagaStep(SagaStepEnum.CREDITED);

        sagaService.handle(event(EventTypes.ACCOUNT_CREDITED, null));

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatusEnum.COMPLETED);
        verify(outboxService, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void lateDebitOnFailedTransactionShouldTriggerRefund() {
        transaction.setStatus(TransactionStatusEnum.FAILED);

        sagaService.handle(event(EventTypes.ACCOUNT_DEBITED, null));

        assertThat(transaction.getSagaStep()).isEqualTo(SagaStepEnum.COMPENSATION_REQUESTED);
        verify(outboxService).enqueue(eq(KafkaTopics.ACCOUNT_COMMANDS), eq(REF),
                eq(EventTypes.ACCOUNT_REFUND_REQUESTED), any());
    }

    @Test
    void resendShouldEnqueueCommandOfCurrentStep() {
        transaction.setSagaStep(SagaStepEnum.COMPENSATION_REQUESTED);

        boolean resent = sagaService.resendCurrentCommand(transaction);

        assertThat(resent).isTrue();
        ArgumentCaptor<AccountCommandPayload> command = ArgumentCaptor.forClass(AccountCommandPayload.class);
        verify(outboxService).enqueue(eq(KafkaTopics.ACCOUNT_COMMANDS), eq(REF),
                eq(EventTypes.ACCOUNT_REFUND_REQUESTED), command.capture());
        assertThat(command.getValue().accountNumber()).isEqualTo("100000000001");
    }

    @Test
    void resendShouldSkipStepWithoutPendingCommand() {
        transaction.setSagaStep(SagaStepEnum.DEBITED);

        assertThat(sagaService.resendCurrentCommand(transaction)).isFalse();
        verify(outboxService, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void lateResultOnManualReviewShouldResumeSaga() {
        transaction.setStatus(TransactionStatusEnum.MANUAL_REVIEW);
        transaction.setRetryCount(3);

        sagaService.handle(event(EventTypes.ACCOUNT_DEBITED, null));

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatusEnum.PROCESSING);
        assertThat(transaction.getSagaStep()).isEqualTo(SagaStepEnum.CREDIT_REQUESTED);
        assertThat(transaction.getRetryCount()).isZero();
    }

    @Test
    void refundAfterNotifiedFailureShouldNotNotifyAgain() {
        transaction.setStatus(TransactionStatusEnum.MANUAL_REVIEW);
        transaction.setSagaStep(SagaStepEnum.COMPENSATION_REQUESTED);
        transaction.setCompletedAt(LocalDateTime.now());

        sagaService.handle(event(EventTypes.ACCOUNT_REFUNDED, null));

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatusEnum.COMPENSATED);
        verify(outboxService, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void lateCreditDuringCompensationShouldEscalateAndStayInManualReview() {
        transaction.setSagaStep(SagaStepEnum.COMPENSATION_REQUESTED);

        sagaService.handle(event(EventTypes.ACCOUNT_CREDITED, null));

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatusEnum.MANUAL_REVIEW);
        assertThat(transaction.getSagaStep()).isEqualTo(SagaStepEnum.COMPENSATION_REQUESTED);
        assertThat(transaction.getFailureReason()).isEqualTo(TransactionSagaService.LATE_CREDIT_REASON);
        verify(outboxService, never()).enqueue(any(), any(), any(), any());

        // Hoàn tiền về sau vẫn được ghi nhận nhưng giao dịch giữ MANUAL_REVIEW để nhân viên thu hồi khoản cộng thừa
        sagaService.handle(event(EventTypes.ACCOUNT_REFUNDED, null));

        assertThat(transaction.getSagaStep()).isEqualTo(SagaStepEnum.COMPENSATED);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatusEnum.MANUAL_REVIEW);
    }

    @Test
    void lateCreditOnFailedDepositShouldEscalate() {
        transaction.setTransactionType(TransactionTypeEnum.DEPOSIT);
        transaction.setStatus(TransactionStatusEnum.FAILED);
        transaction.setSagaStep(SagaStepEnum.CREDIT_REQUESTED);

        sagaService.handle(event(EventTypes.ACCOUNT_CREDITED, null));

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatusEnum.MANUAL_REVIEW);
        verify(outboxService, never()).enqueue(any(), any(), any(), any());
    }

    private EventEnvelope event(String type, String reason) {
        AccountResultPayload payload = new AccountResultPayload(REF, "100000000001", new BigDecimal("500000"),
                reason == null ? new BigDecimal("500000") : null, reason);
        return EventEnvelope.of(type, objectMapper.valueToTree(payload));
    }
}
