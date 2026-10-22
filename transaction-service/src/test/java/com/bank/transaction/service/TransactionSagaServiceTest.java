package com.bank.transaction.service;

import com.bank.common.event.AccountCommandPayload;
import com.bank.common.event.AccountResultPayload;
import com.bank.common.event.EventEnvelope;
import com.bank.common.event.EventTypes;
import com.bank.common.event.InterbankInboundResultPayload;
import com.bank.common.event.InterbankResultPayload;
import com.bank.common.event.InterbankTransferPayload;
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

    @Test
    void debitedInterbankTransferShouldRequestInterbankTransfer() {
        interbankOut();

        sagaService.handle(event(EventTypes.ACCOUNT_DEBITED, null));

        assertThat(transaction.getSagaStep()).isEqualTo(SagaStepEnum.INTERBANK_REQUESTED);
        ArgumentCaptor<InterbankTransferPayload> command = ArgumentCaptor.forClass(InterbankTransferPayload.class);
        verify(outboxService).enqueue(eq(KafkaTopics.INTERBANK_COMMANDS), eq(REF),
                eq(EventTypes.INTERBANK_TRANSFER_REQUESTED), command.capture());
        assertThat(command.getValue().bankCode()).isEqualTo("970436");
        assertThat(command.getValue().beneficiaryAccount()).isEqualTo("0011001234567");
        assertThat(command.getValue().amount()).isEqualByComparingTo("500000");
    }

    @Test
    void interbankCompletedShouldCompleteTransaction() {
        interbankOut();
        transaction.setSagaStep(SagaStepEnum.INTERBANK_REQUESTED);

        sagaService.handle(interbankEvent(EventTypes.INTERBANK_TRANSFER_COMPLETED, null));

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatusEnum.COMPLETED);
        assertThat(transaction.getExternalRef()).isEqualTo("NP0001");
        verify(outboxService).enqueue(eq(KafkaTopics.NOTIFICATION_EVENTS), eq(REF),
                eq(EventTypes.TRANSACTION_COMPLETED), any());
    }

    @Test
    void interbankRejectedShouldRefundAmountAndFee() {
        interbankOut();
        transaction.setSagaStep(SagaStepEnum.INTERBANK_REQUESTED);

        sagaService.handle(interbankEvent(EventTypes.INTERBANK_TRANSFER_REJECTED, "Tài khoản không tồn tại"));

        assertThat(transaction.getSagaStep()).isEqualTo(SagaStepEnum.COMPENSATION_REQUESTED);
        assertThat(transaction.getFailureReason()).contains("Tài khoản không tồn tại");
        ArgumentCaptor<AccountCommandPayload> command = ArgumentCaptor.forClass(AccountCommandPayload.class);
        verify(outboxService).enqueue(eq(KafkaTopics.ACCOUNT_COMMANDS), eq(REF),
                eq(EventTypes.ACCOUNT_REFUND_REQUESTED), command.capture());
        assertThat(command.getValue().accountNumber()).isEqualTo("100000000001");
        assertThat(command.getValue().fee()).isEqualByComparingTo("3000");
    }

    @Test
    void interbankResultInManualReviewShouldStillBeApplied() {
        interbankOut();
        transaction.setSagaStep(SagaStepEnum.INTERBANK_REQUESTED);
        transaction.setStatus(TransactionStatusEnum.MANUAL_REVIEW);

        sagaService.handle(interbankEvent(EventTypes.INTERBANK_TRANSFER_COMPLETED, null));

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatusEnum.COMPLETED);
    }

    @Test
    void timeoutOnInterbankStepShouldResendWithoutRefund() {
        interbankOut();
        transaction.setSagaStep(SagaStepEnum.INTERBANK_REQUESTED);

        boolean resent = sagaService.resendCurrentCommand(transaction);

        assertThat(resent).isTrue();
        verify(outboxService).enqueue(eq(KafkaTopics.INTERBANK_COMMANDS), eq(REF),
                eq(EventTypes.INTERBANK_TRANSFER_REQUESTED), any());
        verify(outboxService, never()).enqueue(eq(KafkaTopics.ACCOUNT_COMMANDS), any(), any(), any());
    }

    @Test
    void lateInterbankSuccessAfterRefundShouldGoToManualReview() {
        interbankOut();
        transaction.setStatus(TransactionStatusEnum.COMPENSATED);
        transaction.setSagaStep(SagaStepEnum.COMPENSATED);

        sagaService.handle(interbankEvent(EventTypes.INTERBANK_TRANSFER_COMPLETED, null));

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatusEnum.MANUAL_REVIEW);
        assertThat(transaction.getFailureReason()).isEqualTo(TransactionSagaService.LATE_INTERBANK_SUCCESS_REASON);
    }

    @Test
    void lateInterbankSuccessBeforeRefundResultShouldKeepManualReviewAfterRefund() {
        interbankOut();
        transaction.setSagaStep(SagaStepEnum.COMPENSATION_REQUESTED);
        sagaService.handle(interbankEvent(EventTypes.INTERBANK_TRANSFER_COMPLETED, null));

        sagaService.handle(event(EventTypes.ACCOUNT_REFUNDED, null));

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatusEnum.MANUAL_REVIEW);
        assertThat(transaction.getSagaStep()).isEqualTo(SagaStepEnum.COMPENSATED);
    }

    @Test
    void creditedInboundShouldCompleteAndReportSettled() {
        interbankIn();

        sagaService.handle(event(EventTypes.ACCOUNT_CREDITED, null));

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatusEnum.COMPLETED);
        ArgumentCaptor<InterbankInboundResultPayload> result =
                ArgumentCaptor.forClass(InterbankInboundResultPayload.class);
        verify(outboxService).enqueue(eq(KafkaTopics.INTERBANK_COMMANDS), eq(REF),
                eq(EventTypes.INTERBANK_INBOUND_SETTLED), result.capture());
        assertThat(result.getValue().napasRef()).isEqualTo("NP0001");
    }

    @Test
    void creditFailedInboundShouldFailAndReportReturned() {
        interbankIn();

        sagaService.handle(event(EventTypes.ACCOUNT_CREDIT_FAILED, "Tài khoản nhận không ở trạng thái hoạt động"));

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatusEnum.FAILED);
        verify(outboxService).enqueue(eq(KafkaTopics.INTERBANK_COMMANDS), eq(REF),
                eq(EventTypes.INTERBANK_INBOUND_RETURNED), any());
        verify(outboxService, never()).enqueue(eq(KafkaTopics.ACCOUNT_COMMANDS), any(), any(), any());
    }

    @Test
    void lateCreditOnReturnedInboundShouldGoToManualReview() {
        interbankIn();
        transaction.setStatus(TransactionStatusEnum.FAILED);

        sagaService.handle(event(EventTypes.ACCOUNT_CREDITED, null));

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatusEnum.MANUAL_REVIEW);
        verify(outboxService, never()).enqueue(eq(KafkaTopics.INTERBANK_COMMANDS), any(), any(), any());
    }

    private void interbankOut() {
        transaction.setTransactionType(TransactionTypeEnum.INTERBANK_OUT);
        transaction.setToAccountNumber(null);
        transaction.setFee(new BigDecimal("3000"));
        transaction.setCounterpartyBankCode("970436");
        transaction.setCounterpartyAccount("0011001234567");
        transaction.setCounterpartyName("TRAN THI B");
    }

    private void interbankIn() {
        transaction.setTransactionType(TransactionTypeEnum.INTERBANK_IN);
        transaction.setFromAccountNumber(null);
        transaction.setSagaStep(SagaStepEnum.CREDIT_REQUESTED);
        transaction.setCounterpartyBankCode("970436");
        transaction.setExternalRef("NP0001");
    }

    private EventEnvelope interbankEvent(String type, String reason) {
        return EventEnvelope.of(type, objectMapper.valueToTree(new InterbankResultPayload(REF, "NP0001", reason)));
    }

    private EventEnvelope event(String type, String reason) {
        AccountResultPayload payload = new AccountResultPayload(REF, "100000000001", new BigDecimal("500000"),
                reason == null ? new BigDecimal("500000") : null, reason);
        return EventEnvelope.of(type, objectMapper.valueToTree(payload));
    }
}
