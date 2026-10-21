package com.bank.transaction.service;

import com.bank.transaction.config.OtpConfig;
import com.bank.transaction.enums.TransactionStatusEnum;
import com.bank.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Hủy giao dịch chờ xác nhận OTP quá {@code bank.otp.pending-timeout} (chưa trừ tiền nên không cần bù trừ).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingTransactionExpiryService {

    private final TransactionRepository transactionRepository;
    private final TransactionPersistenceService persistenceService;
    private final OtpConfig otpConfig;

    @Scheduled(fixedDelayString = "${bank.otp.expiry-check-interval:60s}")
    public void cancelExpiredPending() {
        LocalDateTime before = LocalDateTime.now().minus(otpConfig.getPendingTimeout());
        for (String ref : transactionRepository.findRefsByStatusCreatedBefore(TransactionStatusEnum.PENDING, before)) {
            try {
                persistenceService.failPending(ref, "Hết thời gian xác nhận OTP");
            } catch (RuntimeException e) {
                log.error("Không hủy được giao dịch chờ OTP {}: {}", ref, e.getMessage());
            }
        }
    }
}
