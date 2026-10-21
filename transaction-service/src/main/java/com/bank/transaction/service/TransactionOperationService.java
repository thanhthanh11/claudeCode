package com.bank.transaction.service;

import com.bank.common.audit.AuditActions;
import com.bank.common.audit.AuditPublisher;
import com.bank.common.dto.PageResponse;
import com.bank.common.exception.BadRequestException;
import com.bank.common.exception.ResourceNotFoundException;
import com.bank.transaction.dto.OutboxEventResponse;
import com.bank.transaction.dto.TransactionResponse;
import com.bank.transaction.entity.OutboxEvent;
import com.bank.transaction.entity.Transaction;
import com.bank.transaction.enums.OutboxStatusEnum;
import com.bank.transaction.enums.SagaStepEnum;
import com.bank.transaction.enums.TransactionStatusEnum;
import com.bank.transaction.exception.TransactionNotFoundException;
import com.bank.transaction.repository.OutboxEventRepository;
import com.bank.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Công cụ vận hành (back-office): xem/gửi lại giao dịch bị kẹt (MANUAL_REVIEW) và replay outbox FAILED.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionOperationService {

    private final TransactionRepository transactionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final TransactionSagaService sagaService;
    private final AuditPublisher auditPublisher;

    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> listByStatus(TransactionStatusEnum status, Pageable pageable) {
        return PageResponse.from(transactionRepository.findByStatus(status, pageable)
                .map(TransactionService::toResponse));
    }

    /**
     * Gửi lại lệnh của bước Saga hiện tại và cho job quá hạn theo dõi lại từ đầu (RETRY_COUNT = 0).
     * Dùng sau khi đã khắc phục nguyên nhân (Kafka, account-service, mở lại tài khoản...).
     */
    @Transactional
    public TransactionResponse retry(String transactionRef) {
        Transaction transaction = transactionRepository.findByTransactionRefForUpdate(transactionRef)
                .orElseThrow(() -> new TransactionNotFoundException(transactionRef));
        boolean retryable = transaction.getStatus() == TransactionStatusEnum.MANUAL_REVIEW
                || transaction.getStatus() == TransactionStatusEnum.PROCESSING
                || (transaction.getStatus() == TransactionStatusEnum.FAILED
                && transaction.getSagaStep() == SagaStepEnum.COMPENSATION_REQUESTED);
        if (!retryable || !sagaService.resendCurrentCommand(transaction)) {
            throw new BadRequestException("TRANSACTION_NOT_RETRYABLE",
                    "Giao dịch không ở bước chờ kết quả (status=" + transaction.getStatus() + ")");
        }
        if (transaction.getStatus() == TransactionStatusEnum.MANUAL_REVIEW) {
            // Đã báo thất bại (completedAt != null) thì trở lại FAILED chờ hoàn tiền, ngược lại tiếp tục PROCESSING
            transaction.setStatus(transaction.getCompletedAt() != null
                    ? TransactionStatusEnum.FAILED : TransactionStatusEnum.PROCESSING);
        }
        transaction.setRetryCount(0);
        log.info("Gửi lại thủ công bước {} của giao dịch {}", transaction.getSagaStep(), transactionRef);
        auditPublisher.publish(AuditActions.TRANSACTION_RETRIED, "TRANSACTION", transactionRef,
                "step=" + transaction.getSagaStep());
        return TransactionService.toResponse(transaction);
    }

    @Transactional(readOnly = true)
    public PageResponse<OutboxEventResponse> listOutbox(OutboxStatusEnum status, Pageable pageable) {
        return PageResponse.from(outboxEventRepository.findByStatus(status, pageable)
                .map(TransactionOperationService::toResponse));
    }

    /** Đưa event outbox FAILED về NEW để publisher gửi lại. */
    @Transactional
    public OutboxEventResponse replayOutbox(Long id) {
        OutboxEvent event = outboxEventRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("OUTBOX_EVENT_NOT_FOUND", "Không tìm thấy outbox #" + id));
        if (event.getStatus() != OutboxStatusEnum.FAILED) {
            throw new BadRequestException("OUTBOX_NOT_FAILED", "Chỉ replay được event ở trạng thái FAILED");
        }
        event.setStatus(OutboxStatusEnum.NEW);
        event.setRetryCount(0);
        auditPublisher.publish(AuditActions.OUTBOX_EVENT_REPLAYED, "OUTBOX_EVENT", id,
                event.getEventType() + " / " + event.getAggregateId());
        return toResponse(event);
    }

    private static OutboxEventResponse toResponse(OutboxEvent event) {
        return new OutboxEventResponse(event.getId(), event.getAggregateId(), event.getEventType(), event.getTopic(),
                event.getStatus(), event.getRetryCount(), event.getCreatedAt(), event.getPublishedAt());
    }
}
