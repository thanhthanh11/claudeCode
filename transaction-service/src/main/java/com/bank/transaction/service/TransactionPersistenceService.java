package com.bank.transaction.service;

import com.bank.common.event.AccountCommandPayload;
import com.bank.common.event.EventTypes;
import com.bank.common.event.KafkaTopics;
import com.bank.transaction.dto.TransactionLimitResponse;
import com.bank.transaction.entity.Transaction;
import com.bank.transaction.enums.SagaStepEnum;
import com.bank.transaction.enums.TransactionStatusEnum;
import com.bank.transaction.enums.TransactionTypeEnum;
import com.bank.transaction.exception.TransactionNotFoundException;
import com.bank.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Lưu giao dịch mới (kèm kiểm tra hạn mức) và lệnh đầu tiên của Saga vào outbox trong cùng một transaction.
 * Tách bean riêng để {@code TransactionService} bắt được lỗi trùng Idempotency-Key sau khi rollback.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionPersistenceService {

    private static final int MAX_REASON_LENGTH = 500;

    private final TransactionRepository transactionRepository;
    private final OutboxService outboxService;
    private final TransactionLimitService limitService;

    /**
     * @param limitAccountNumber tài khoản bị tính hạn mức (nguồn với chuyển/rút, nhận với nộp tiền)
     */
    @Transactional
    public Transaction createWithCommand(Transaction transaction, String commandType, String targetAccountNumber,
                                         TransactionLimitResponse limit, String limitAccountNumber) {
        limitService.checkAndLock(limit, transaction.getTransactionType(), limitAccountNumber, transaction.getAmount());
        transactionRepository.saveAndFlush(transaction);
        enqueueFirstCommand(transaction, commandType, targetAccountNumber);
        return transaction;
    }

    /** Giao dịch chờ xác nhận OTP: tính hạn mức ngay (tránh tạo nhiều lệnh chờ vượt hạn mức), chưa gửi lệnh. */
    @Transactional
    public Transaction createPending(Transaction transaction, TransactionLimitResponse limit, String limitAccountNumber) {
        limitService.checkAndLock(limit, transaction.getTransactionType(), limitAccountNumber, transaction.getAmount());
        transaction.setStatus(TransactionStatusEnum.PENDING);
        transactionRepository.saveAndFlush(transaction);
        return transaction;
    }

    /** OTP hợp lệ: PENDING → PROCESSING và gửi lệnh trừ tiền. Chỉ áp dụng cho chuyển khoản (nội bộ / liên ngân hàng). */
    @Transactional
    public Transaction confirmPending(String transactionRef) {
        Transaction transaction = transactionRepository.findByTransactionRefForUpdate(transactionRef)
                .orElseThrow(() -> new TransactionNotFoundException(transactionRef));
        if (transaction.getStatus() != TransactionStatusEnum.PENDING) {
            return transaction;
        }
        if (transaction.getTransactionType() != TransactionTypeEnum.TRANSFER
                && transaction.getTransactionType() != TransactionTypeEnum.INTERBANK_OUT) {
            throw new IllegalStateException("Chỉ giao dịch chuyển khoản / liên ngân hàng dùng OTP: " + transactionRef);
        }
        transaction.setStatus(TransactionStatusEnum.PROCESSING);
        transaction.setSagaStep(SagaStepEnum.DEBIT_REQUESTED);
        transaction.setRetryCount(0);
        enqueueFirstCommand(transaction, EventTypes.ACCOUNT_DEBIT_REQUESTED, transaction.getFromAccountNumber());
        log.info("Giao dịch {} đã xác nhận OTP", transactionRef);
        return transaction;
    }

    /** Hủy giao dịch đang chờ OTP (sai quá số lần, hết hạn). Chưa có tiền nào bị trừ nên không cần bù trừ. */
    @Transactional
    public Transaction failPending(String transactionRef, String reason) {
        Transaction transaction = transactionRepository.findByTransactionRefForUpdate(transactionRef)
                .orElseThrow(() -> new TransactionNotFoundException(transactionRef));
        if (transaction.getStatus() == TransactionStatusEnum.PENDING) {
            transaction.setStatus(TransactionStatusEnum.FAILED);
            transaction.setFailureReason(reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH));
            transaction.setCompletedAt(LocalDateTime.now());
            log.info("Hủy giao dịch chờ OTP {}: {}", transactionRef, reason);
        }
        return transaction;
    }

    private void enqueueFirstCommand(Transaction transaction, String commandType, String targetAccountNumber) {
        // Phí chỉ đi kèm lệnh trừ tiền; lệnh cộng tiền (nộp tiền) cộng đúng số tiền giao dịch
        boolean debit = EventTypes.ACCOUNT_DEBIT_REQUESTED.equals(commandType);
        outboxService.enqueue(KafkaTopics.ACCOUNT_COMMANDS, transaction.getTransactionRef(), commandType,
                new AccountCommandPayload(transaction.getTransactionRef(), targetAccountNumber,
                        transaction.getAmount(), transaction.getCurrency(), transaction.getDescription(),
                        debit ? transaction.getFee() : null));
    }
}
