package com.bank.transaction.service;

import com.bank.common.event.AccountCommandPayload;
import com.bank.common.event.AccountResultPayload;
import com.bank.common.event.EventEnvelope;
import com.bank.common.event.EventSerializer;
import com.bank.common.event.EventTypes;
import com.bank.common.event.InterbankInboundResultPayload;
import com.bank.common.event.InterbankResultPayload;
import com.bank.common.event.InterbankTransferPayload;
import com.bank.common.event.KafkaTopics;
import com.bank.common.event.TransactionNotificationPayload;
import com.bank.transaction.entity.Transaction;
import com.bank.transaction.enums.SagaStepEnum;
import com.bank.transaction.enums.TransactionStatusEnum;
import com.bank.transaction.enums.TransactionTypeEnum;
import com.bank.transaction.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Điều phối Saga theo kết quả từ account-service và interbank-service. Mỗi event chỉ được áp dụng khi giao dịch
 * đang ở đúng bước chờ event đó; event lặp lại hoặc đến muộn bị bỏ qua (idempotent).
 *
 * <pre>
 * TRANSFER:   DEBIT_REQUESTED --debited--> CREDIT_REQUESTED --credited--> COMPLETED
 *                    |                            |
 *               debit failed                credit failed --> COMPENSATION_REQUESTED --refunded--> COMPENSATED
 *                    v
 *                 FAILED
 * WITHDRAWAL: DEBIT_REQUESTED --debited--> COMPLETED | --debit failed--> FAILED
 * DEPOSIT:    CREDIT_REQUESTED --credited--> COMPLETED | --credit failed--> FAILED
 *
 * INTERBANK_OUT: DEBIT_REQUESTED --debited--> INTERBANK_REQUESTED --interbank completed--> COMPLETED
 *                       |                            |
 *                  debit failed              interbank rejected --> COMPENSATION_REQUESTED --refunded--> COMPENSATED
 *                       v
 *                    FAILED
 *   Không có kết quả từ Napas (timeout) → KHÔNG tự hoàn tiền (tiền có thể đã sang ngân hàng kia): job quá hạn gửi
 *   lại lệnh (interbank-service idempotent) rồi MANUAL_REVIEW; kết quả đến muộn vẫn được áp dụng.
 * INTERBANK_IN:  CREDIT_REQUESTED --credited--> COMPLETED (+ INBOUND_SETTLED)
 *                                 --credit failed--> FAILED (+ INBOUND_RETURNED: Napas trả tiền về ngân hàng gửi)
 *
 * CREDITED đến muộn (lệnh cộng tiền gửi lại thành công sau khi chuyển khoản đã sang COMPENSATION_REQUESTED /
 * COMPENSATED, hoặc nộp tiền / tiền đến đã FAILED) --> MANUAL_REVIEW (giữ nguyên sagaStep, cần thu hồi thủ công).
 * Napas báo kết quả trái ngược sau khi giao dịch đã kết thúc (thành công sau khi đã hoàn tiền, từ chối sau khi đã
 * COMPLETED) --> MANUAL_REVIEW.
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionSagaService {

    private static final int MAX_REASON_LENGTH = 500;
    static final String LATE_CREDIT_REASON = "Cộng tiền đến muộn sau khi đã bù trừ/thất bại — cần thu hồi thủ công";
    static final String LATE_INTERBANK_SUCCESS_REASON =
            "Napas báo chuyển thành công sau khi đã hoàn tiền — cần thu hồi thủ công";
    static final String LATE_INTERBANK_REJECT_REASON =
            "Napas báo từ chối sau khi giao dịch đã hoàn tất — cần hoàn tiền thủ công khi Napas trả tiền về";

    private final TransactionRepository transactionRepository;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    @Transactional
    public void handle(EventEnvelope envelope) {
        switch (envelope.eventType()) {
            case EventTypes.INTERBANK_TRANSFER_COMPLETED, EventTypes.INTERBANK_TRANSFER_REJECTED ->
                    handleInterbankResult(envelope);
            default -> handleAccountResult(envelope);
        }
    }

    private void handleInterbankResult(EventEnvelope envelope) {
        InterbankResultPayload result = EventSerializer.payload(objectMapper, envelope, InterbankResultPayload.class);
        Optional<Transaction> found = transactionRepository.findByTransactionRefForUpdate(result.transactionRef());
        if (found.isEmpty()) {
            log.warn("Nhận {} cho giao dịch không tồn tại: {}", envelope.eventType(), result.transactionRef());
            return;
        }
        if (EventTypes.INTERBANK_TRANSFER_COMPLETED.equals(envelope.eventType())) {
            onInterbankCompleted(found.get(), result.napasRef());
        } else {
            onInterbankRejected(found.get(), result.reason(), result.napasRef());
        }
    }

    private void handleAccountResult(EventEnvelope envelope) {
        AccountResultPayload result = EventSerializer.payload(objectMapper, envelope, AccountResultPayload.class);
        Optional<Transaction> found = transactionRepository.findByTransactionRefForUpdate(result.transactionRef());
        if (found.isEmpty()) {
            log.warn("Nhận {} cho giao dịch không tồn tại: {}", envelope.eventType(), result.transactionRef());
            return;
        }
        Transaction transaction = found.get();
        switch (envelope.eventType()) {
            case EventTypes.ACCOUNT_DEBITED -> onDebited(transaction);
            case EventTypes.ACCOUNT_DEBIT_FAILED -> onDebitFailed(transaction, result.reason());
            case EventTypes.ACCOUNT_CREDITED -> onCredited(transaction);
            case EventTypes.ACCOUNT_CREDIT_FAILED -> onCreditFailed(transaction, result.reason());
            case EventTypes.ACCOUNT_REFUNDED -> onRefunded(transaction);
            case EventTypes.ACCOUNT_REFUND_FAILED -> onRefundFailed(transaction, result.reason());
            default -> log.warn("Bỏ qua event không hỗ trợ: {}", envelope.eventType());
        }
    }

    /**
     * Gửi lại lệnh của bước Saga hiện tại (dùng khi quá thời gian chờ kết quả). An toàn khi lệnh đã được xử lý:
     * account-service nhận ra bút toán đã tồn tại và gửi lại kết quả cũ. Phải gọi trong transaction đang khóa giao dịch.
     *
     * @return {@code false} nếu bước hiện tại không có lệnh nào đang chờ kết quả
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean resendCurrentCommand(Transaction transaction) {
        switch (transaction.getSagaStep()) {
            case DEBIT_REQUESTED -> enqueueCommand(transaction, EventTypes.ACCOUNT_DEBIT_REQUESTED,
                    transaction.getFromAccountNumber(), transaction.getDescription());
            case CREDIT_REQUESTED -> enqueueCommand(transaction, EventTypes.ACCOUNT_CREDIT_REQUESTED,
                    transaction.getToAccountNumber(), transaction.getDescription());
            // interbank-service bỏ qua lệnh trùng transactionRef và tự tra soát Napas, không gửi tiền hai lần
            case INTERBANK_REQUESTED -> enqueueInterbankTransfer(transaction);
            case COMPENSATION_REQUESTED -> enqueueCommand(transaction, EventTypes.ACCOUNT_REFUND_REQUESTED,
                    transaction.getFromAccountNumber(), refundDescription(transaction));
            case null, default -> {
                return false;
            }
        }
        return true;
    }

    private void onDebited(Transaction transaction) {
        if (isWaitingFor(transaction, SagaStepEnum.DEBIT_REQUESTED)) {
            if (transaction.getTransactionType() == TransactionTypeEnum.TRANSFER) {
                transaction.setSagaStep(SagaStepEnum.CREDIT_REQUESTED);
                sendCommand(transaction, EventTypes.ACCOUNT_CREDIT_REQUESTED, transaction.getToAccountNumber(),
                        transaction.getDescription());
            } else if (transaction.getTransactionType() == TransactionTypeEnum.INTERBANK_OUT) {
                transaction.setSagaStep(SagaStepEnum.INTERBANK_REQUESTED);
                resetForNewStep(transaction);
                enqueueInterbankTransfer(transaction);
            } else {
                transaction.setSagaStep(SagaStepEnum.DEBITED);
                complete(transaction);
            }
        } else if (transaction.getStatus() == TransactionStatusEnum.FAILED
                && transaction.getSagaStep() == SagaStepEnum.DEBIT_REQUESTED) {
            // Lệnh trừ tiền bị xử lý lại sau khi giao dịch đã đánh dấu thất bại → hoàn tiền để không mất tiền khách
            log.warn("Giao dịch {} đã FAILED nhưng nhận ACCOUNT_DEBITED — gửi lệnh hoàn tiền",
                    transaction.getTransactionRef());
            transaction.setSagaStep(SagaStepEnum.COMPENSATION_REQUESTED);
            sendCommand(transaction, EventTypes.ACCOUNT_REFUND_REQUESTED, transaction.getFromAccountNumber(),
                    refundDescription(transaction));
        } else {
            logIgnored(transaction, EventTypes.ACCOUNT_DEBITED);
        }
    }

    private void onDebitFailed(Transaction transaction, String reason) {
        if (isWaitingFor(transaction, SagaStepEnum.DEBIT_REQUESTED)) {
            fail(transaction, reason);
        } else {
            logIgnored(transaction, EventTypes.ACCOUNT_DEBIT_FAILED);
        }
    }

    private void onCredited(Transaction transaction) {
        if (isWaitingFor(transaction, SagaStepEnum.CREDIT_REQUESTED)) {
            transaction.setSagaStep(SagaStepEnum.CREDITED);
            complete(transaction);
            if (transaction.getTransactionType() == TransactionTypeEnum.INTERBANK_IN) {
                notifyInboundResult(transaction, EventTypes.INTERBANK_INBOUND_SETTLED, null);
            }
        } else if (isLateCredit(transaction)) {
            // Lệnh cộng tiền gửi lại (do quá hạn) thành công sau khi giao dịch đã bị coi là thất bại: tài khoản nhận
            // đã được cộng tiền trong khi nguồn đã/đang được hoàn (chuyển khoản) hoặc tiền mặt đã trả lại khách (nộp
            // tiền). Không tự trừ tiền khách — chuyển MANUAL_REVIEW để nhân viên thu hồi.
            log.error("Giao dịch {} ({}/{}) nhận ACCOUNT_CREDITED đến muộn — chuyển MANUAL_REVIEW, cần thu hồi thủ công",
                    transaction.getTransactionRef(), transaction.getStatus(), transaction.getSagaStep());
            transaction.setStatus(TransactionStatusEnum.MANUAL_REVIEW);
            transaction.setFailureReason(LATE_CREDIT_REASON);
        } else {
            logIgnored(transaction, EventTypes.ACCOUNT_CREDITED);
        }
    }

    /** CREDITED đến khi chuyển khoản đã sang bù trừ, hoặc nộp tiền / tiền đến đã FAILED ở bước cộng tiền. */
    private static boolean isLateCredit(Transaction transaction) {
        SagaStepEnum step = transaction.getSagaStep();
        if (transaction.getTransactionType() == TransactionTypeEnum.TRANSFER) {
            return step == SagaStepEnum.COMPENSATION_REQUESTED || step == SagaStepEnum.COMPENSATED;
        }
        return (transaction.getTransactionType() == TransactionTypeEnum.DEPOSIT
                || transaction.getTransactionType() == TransactionTypeEnum.INTERBANK_IN)
                && transaction.getStatus() == TransactionStatusEnum.FAILED
                && step == SagaStepEnum.CREDIT_REQUESTED;
    }

    private void onCreditFailed(Transaction transaction, String reason) {
        if (!isWaitingFor(transaction, SagaStepEnum.CREDIT_REQUESTED)) {
            logIgnored(transaction, EventTypes.ACCOUNT_CREDIT_FAILED);
            return;
        }
        if (transaction.getTransactionType() == TransactionTypeEnum.TRANSFER) {
            // Đã trừ tiền tài khoản nguồn → bù trừ bằng lệnh hoàn tiền
            transaction.setSagaStep(SagaStepEnum.COMPENSATION_REQUESTED);
            transaction.setFailureReason(truncate(reason));
            sendCommand(transaction, EventTypes.ACCOUNT_REFUND_REQUESTED, transaction.getFromAccountNumber(),
                    refundDescription(transaction));
        } else {
            fail(transaction, reason);
            if (transaction.getTransactionType() == TransactionTypeEnum.INTERBANK_IN) {
                // Không cộng được (tài khoản đóng / phong tỏa / sai loại tiền) → báo Napas trả tiền về ngân hàng gửi
                notifyInboundResult(transaction, EventTypes.INTERBANK_INBOUND_RETURNED, transaction.getFailureReason());
            }
        }
    }

    private void onInterbankCompleted(Transaction transaction, String napasRef) {
        if (isWaitingFor(transaction, SagaStepEnum.INTERBANK_REQUESTED)) {
            transaction.setExternalRef(napasRef);
            complete(transaction);
        } else if (transaction.getTransactionType() == TransactionTypeEnum.INTERBANK_OUT
                && (transaction.getSagaStep() == SagaStepEnum.COMPENSATION_REQUESTED
                || transaction.getSagaStep() == SagaStepEnum.COMPENSATED)) {
            // Tiền đã sang ngân hàng kia trong khi khách đã/đang được hoàn tiền → không tự trừ lại tiền khách
            log.error("Giao dịch {} nhận INTERBANK_TRANSFER_COMPLETED sau khi đã hoàn tiền — chuyển MANUAL_REVIEW",
                    transaction.getTransactionRef());
            transaction.setExternalRef(napasRef);
            transaction.setStatus(TransactionStatusEnum.MANUAL_REVIEW);
            transaction.setFailureReason(LATE_INTERBANK_SUCCESS_REASON);
        } else {
            logIgnored(transaction, EventTypes.INTERBANK_TRANSFER_COMPLETED);
        }
    }

    private void onInterbankRejected(Transaction transaction, String reason, String napasRef) {
        if (isWaitingFor(transaction, SagaStepEnum.INTERBANK_REQUESTED)) {
            // Napas / ngân hàng nhận từ chối: tiền chưa rời ngân hàng → hoàn cả tiền và phí về tài khoản nguồn
            transaction.setSagaStep(SagaStepEnum.COMPENSATION_REQUESTED);
            transaction.setExternalRef(napasRef);
            transaction.setFailureReason(truncate("Ngân hàng nhận từ chối: " + reason));
            sendCommand(transaction, EventTypes.ACCOUNT_REFUND_REQUESTED, transaction.getFromAccountNumber(),
                    refundDescription(transaction));
        } else if (transaction.getTransactionType() == TransactionTypeEnum.INTERBANK_OUT
                && transaction.getStatus() == TransactionStatusEnum.COMPLETED) {
            log.error("Giao dịch {} nhận INTERBANK_TRANSFER_REJECTED sau khi đã COMPLETED — chuyển MANUAL_REVIEW",
                    transaction.getTransactionRef());
            transaction.setStatus(TransactionStatusEnum.MANUAL_REVIEW);
            transaction.setFailureReason(LATE_INTERBANK_REJECT_REASON);
        } else {
            logIgnored(transaction, EventTypes.INTERBANK_TRANSFER_REJECTED);
        }
    }

    private void onRefunded(Transaction transaction) {
        if (transaction.getSagaStep() != SagaStepEnum.COMPENSATION_REQUESTED) {
            logIgnored(transaction, EventTypes.ACCOUNT_REFUNDED);
            return;
        }
        // fail() đặt completedAt và đã gửi TRANSACTION_FAILED; dựa vào completedAt thay vì status vì giao dịch
        // có thể đã chuyển FAILED → MANUAL_REVIEW trong lúc chờ hoàn tiền
        boolean alreadyNotified = transaction.getCompletedAt() != null;
        // Đã đánh dấu cộng tiền / chuyển liên ngân hàng thành công đến muộn thì giữ MANUAL_REVIEW để nhân viên vẫn
        // thấy và thu hồi khoản tiền đi thừa
        if (!requiresManualRecovery(transaction)) {
            transaction.setStatus(TransactionStatusEnum.COMPENSATED);
        }
        transaction.setSagaStep(SagaStepEnum.COMPENSATED);
        transaction.setCompletedAt(LocalDateTime.now());
        log.info("Giao dịch {} đã hoàn tiền (COMPENSATED)", transaction.getTransactionRef());
        if (!alreadyNotified) {
            notify(transaction, EventTypes.TRANSACTION_FAILED);
        }
    }

    private void onRefundFailed(Transaction transaction, String reason) {
        log.error("HOÀN TIỀN THẤT BẠI cho giao dịch {}: {} — cần xử lý thủ công",
                transaction.getTransactionRef(), reason);
        if (!requiresManualRecovery(transaction)) {
            transaction.setFailureReason(truncate("Hoàn tiền thất bại: " + reason));
        }
    }

    private void complete(Transaction transaction) {
        transaction.setStatus(TransactionStatusEnum.COMPLETED);
        transaction.setCompletedAt(LocalDateTime.now());
        log.info("Giao dịch {} hoàn tất", transaction.getTransactionRef());
        notify(transaction, EventTypes.TRANSACTION_COMPLETED);
    }

    private void fail(Transaction transaction, String reason) {
        transaction.setStatus(TransactionStatusEnum.FAILED);
        transaction.setFailureReason(truncate(reason));
        transaction.setCompletedAt(LocalDateTime.now());
        log.info("Giao dịch {} thất bại: {}", transaction.getTransactionRef(), reason);
        notify(transaction, EventTypes.TRANSACTION_FAILED);
    }

    /** Gửi lệnh của bước Saga mới: bước mới có bộ đếm gửi lại riêng và lại được job quá hạn theo dõi. */
    private void sendCommand(Transaction transaction, String commandType, String accountNumber, String description) {
        resetForNewStep(transaction);
        enqueueCommand(transaction, commandType, accountNumber, description);
    }

    private static void resetForNewStep(Transaction transaction) {
        transaction.setRetryCount(0);
        if (transaction.getStatus() == TransactionStatusEnum.MANUAL_REVIEW) {
            transaction.setStatus(TransactionStatusEnum.PROCESSING);
        }
    }

    /** Lệnh chuyển tiền ra ngân hàng khác (sau khi đã trừ tiền nguồn); interbank-service idempotent theo transactionRef. */
    private void enqueueInterbankTransfer(Transaction transaction) {
        outboxService.enqueue(KafkaTopics.INTERBANK_COMMANDS, transaction.getTransactionRef(),
                EventTypes.INTERBANK_TRANSFER_REQUESTED,
                new InterbankTransferPayload(transaction.getTransactionRef(), transaction.getFromAccountNumber(),
                        transaction.getCounterpartyBankCode(), transaction.getCounterpartyAccount(),
                        transaction.getCounterpartyName(), transaction.getAmount(), transaction.getCurrency(),
                        transaction.getDescription()));
    }

    /** Báo interbank-service kết quả tiền đến để trả lời Napas (đã cộng / trả lại ngân hàng gửi). */
    private void notifyInboundResult(Transaction transaction, String eventType, String reason) {
        outboxService.enqueue(KafkaTopics.INTERBANK_COMMANDS, transaction.getTransactionRef(), eventType,
                new InterbankInboundResultPayload(transaction.getExternalRef(), transaction.getTransactionRef(),
                        reason));
    }

    private static boolean requiresManualRecovery(Transaction transaction) {
        String reason = transaction.getFailureReason();
        return LATE_CREDIT_REASON.equals(reason) || LATE_INTERBANK_SUCCESS_REASON.equals(reason);
    }

    /** Lệnh trừ tiền và hoàn tiền mang theo phí (trừ / hoàn cả {@code amount + fee}); lệnh cộng tiền thì không. */
    private void enqueueCommand(Transaction transaction, String commandType, String accountNumber,
                                String description) {
        boolean withFee = EventTypes.ACCOUNT_DEBIT_REQUESTED.equals(commandType)
                || EventTypes.ACCOUNT_REFUND_REQUESTED.equals(commandType);
        outboxService.enqueue(KafkaTopics.ACCOUNT_COMMANDS, transaction.getTransactionRef(), commandType,
                new AccountCommandPayload(transaction.getTransactionRef(), accountNumber, transaction.getAmount(),
                        transaction.getCurrency(), description, withFee ? transaction.getFee() : null));
    }

    private void notify(Transaction transaction, String eventType) {
        outboxService.enqueue(KafkaTopics.NOTIFICATION_EVENTS, transaction.getTransactionRef(), eventType,
                new TransactionNotificationPayload(transaction.getTransactionRef(),
                        transaction.getTransactionType().name(), transaction.getStatus().name(),
                        transaction.getFromAccountNumber(), transaction.getToAccountNumber(), transaction.getAmount(),
                        transaction.getCurrency(), transaction.getFailureReason(), Instant.now(), transaction.getFee()));
    }

    private static boolean isWaitingFor(Transaction transaction, SagaStepEnum step) {
        TransactionStatusEnum status = transaction.getStatus();
        return (status == TransactionStatusEnum.PROCESSING || status == TransactionStatusEnum.MANUAL_REVIEW)
                && transaction.getSagaStep() == step;
    }

    private static String refundDescription(Transaction transaction) {
        return "Hoàn tiền giao dịch " + transaction.getTransactionRef();
    }

    private static void logIgnored(Transaction transaction, String eventType) {
        log.debug("Bỏ qua {} cho giao dịch {} (status={}, step={})", eventType, transaction.getTransactionRef(),
                transaction.getStatus(), transaction.getSagaStep());
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }
}
