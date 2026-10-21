package com.bank.transaction.service;

import com.bank.common.event.AccountCommandPayload;
import com.bank.common.event.AccountResultPayload;
import com.bank.common.event.EventEnvelope;
import com.bank.common.event.EventSerializer;
import com.bank.common.event.EventTypes;
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
 * Điều phối Saga theo kết quả từ account-service. Mỗi event chỉ được áp dụng khi giao dịch đang ở
 * đúng bước chờ event đó; event lặp lại hoặc đến muộn bị bỏ qua (idempotent).
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
 * CREDITED đến muộn (lệnh cộng tiền gửi lại thành công sau khi chuyển khoản đã sang COMPENSATION_REQUESTED /
 * COMPENSATED, hoặc nộp tiền đã FAILED) --> MANUAL_REVIEW (giữ nguyên sagaStep, cần thu hồi thủ công)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionSagaService {

    private static final int MAX_REASON_LENGTH = 500;
    static final String LATE_CREDIT_REASON = "Cộng tiền đến muộn sau khi đã bù trừ/thất bại — cần thu hồi thủ công";

    private final TransactionRepository transactionRepository;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    @Transactional
    public void handle(EventEnvelope envelope) {
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

    /** CREDITED đến khi chuyển khoản đã sang bù trừ, hoặc nộp tiền đã FAILED ở bước cộng tiền. */
    private static boolean isLateCredit(Transaction transaction) {
        SagaStepEnum step = transaction.getSagaStep();
        if (transaction.getTransactionType() == TransactionTypeEnum.TRANSFER) {
            return step == SagaStepEnum.COMPENSATION_REQUESTED || step == SagaStepEnum.COMPENSATED;
        }
        return transaction.getTransactionType() == TransactionTypeEnum.DEPOSIT
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
        // Đã đánh dấu cộng tiền đến muộn thì giữ MANUAL_REVIEW để nhân viên vẫn thấy và thu hồi khoản cộng thừa
        if (!LATE_CREDIT_REASON.equals(transaction.getFailureReason())) {
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
        if (!LATE_CREDIT_REASON.equals(transaction.getFailureReason())) {
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
        transaction.setRetryCount(0);
        if (transaction.getStatus() == TransactionStatusEnum.MANUAL_REVIEW) {
            transaction.setStatus(TransactionStatusEnum.PROCESSING);
        }
        enqueueCommand(transaction, commandType, accountNumber, description);
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
