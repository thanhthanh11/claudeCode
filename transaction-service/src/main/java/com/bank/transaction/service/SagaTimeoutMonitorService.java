package com.bank.transaction.service;

import com.bank.transaction.entity.Transaction;
import com.bank.transaction.enums.TransactionStatusEnum;
import com.bank.transaction.repository.TransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Phát hiện giao dịch Saga chờ kết quả từ account-service quá lâu (lệnh hoặc kết quả bị mất, message vào DLT...)
 * và gửi lại lệnh của bước hiện tại. Sau {@code max-retries} lần vẫn không có kết quả thì chuyển
 * {@link TransactionStatusEnum#MANUAL_REVIEW} để nhân viên xử lý. Mỗi lần gửi lại cập nhật UPDATED_AT,
 * nên giữa hai lần gửi luôn cách nhau ít nhất {@code step-timeout}.
 */
@Slf4j
@Service
public class SagaTimeoutMonitorService {

    private final TransactionRepository transactionRepository;
    private final TransactionSagaService sagaService;
    private final Duration stepTimeout;
    private final int maxRetries;
    private final int batchSize;

    public SagaTimeoutMonitorService(TransactionRepository transactionRepository,
                                     TransactionSagaService sagaService,
                                     @Value("${bank.saga.step-timeout:2m}") Duration stepTimeout,
                                     @Value("${bank.saga.max-retries:3}") int maxRetries,
                                     @Value("${bank.saga.batch-size:50}") int batchSize) {
        this.transactionRepository = transactionRepository;
        this.sagaService = sagaService;
        this.stepTimeout = stepTimeout;
        this.maxRetries = maxRetries;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${bank.saga.poll-interval:30s}")
    @Transactional
    public void resendStuckCommands() {
        List<Transaction> stuck = transactionRepository.lockStuckBatch(LocalDateTime.now().minus(stepTimeout),
                batchSize);
        for (Transaction transaction : stuck) {
            if (transaction.getRetryCount() >= maxRetries) {
                escalate(transaction);
            } else if (sagaService.resendCurrentCommand(transaction)) {
                transaction.setRetryCount(transaction.getRetryCount() + 1);
                log.warn("Giao dịch {} chờ kết quả bước {} quá {} — gửi lại lệnh (lần {}/{})",
                        transaction.getTransactionRef(), transaction.getSagaStep(), stepTimeout,
                        transaction.getRetryCount(), maxRetries);
            } else {
                escalate(transaction);
            }
        }
    }

    private void escalate(Transaction transaction) {
        transaction.setStatus(TransactionStatusEnum.MANUAL_REVIEW);
        log.error("Giao dịch {} không có kết quả bước {} sau {} lần gửi lại, chuyển MANUAL_REVIEW — cần xử lý thủ công",
                transaction.getTransactionRef(), transaction.getSagaStep(), transaction.getRetryCount());
    }
}
