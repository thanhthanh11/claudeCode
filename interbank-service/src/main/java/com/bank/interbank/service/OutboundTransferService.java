package com.bank.interbank.service;

import com.bank.common.audit.AuditActions;
import com.bank.common.audit.AuditPublisher;
import com.bank.common.dto.PageResponse;
import com.bank.common.event.InterbankTransferPayload;
import com.bank.common.exception.ResourceNotFoundException;
import com.bank.common.util.MaskingUtils;
import com.bank.interbank.dto.NapasResult;
import com.bank.interbank.dto.OutboundTransferResponse;
import com.bank.interbank.entity.OutboundTransfer;
import com.bank.interbank.enums.NapasResultStatusEnum;
import com.bank.interbank.enums.OutboundStatusEnum;
import com.bank.interbank.repository.OutboundTransferRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Lệnh chuyển tiền ra ngân hàng khác.
 * <pre>
 * NEW --gửi Napas--> COMPLETED | REJECTED | UNKNOWN (không phản hồi / Napas đang xử lý)
 * UNKNOWN --tra soát (backoff)--> COMPLETED | REJECTED | UNKNOWN | NEW (Napas chưa nhận lệnh → gửi lại)
 * </pre>
 * UNKNOWN quá {@code bank.interbank.max-attempts} lần tra soát thì dừng tự động, chờ nhân viên tra soát thủ công;
 * trong lúc đó Saga ở transaction-service chuyển MANUAL_REVIEW nhưng <b>không</b> hoàn tiền.
 * Job giữ khóa dòng trong lúc gọi Napas nên hai instance không gửi cùng lệnh; Napas vẫn idempotent theo
 * transactionRef cho trường hợp gửi lại sau khi mất phản hồi.
 */
@Slf4j
@Service
public class OutboundTransferService {

    private static final int MAX_REASON_LENGTH = 500;
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    private final OutboundTransferRepository outboundRepository;
    private final NapasGatewayService napasGateway;
    private final AuditPublisher auditPublisher;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration inquiryInterval;

    public OutboundTransferService(OutboundTransferRepository outboundRepository, NapasGatewayService napasGateway,
                                   AuditPublisher auditPublisher,
                                   @Value("${bank.interbank.batch-size:20}") int batchSize,
                                   @Value("${bank.interbank.max-attempts:20}") int maxAttempts,
                                   @Value("${bank.interbank.inquiry-interval:30s}") Duration inquiryInterval) {
        this.outboundRepository = outboundRepository;
        this.napasGateway = napasGateway;
        this.auditPublisher = auditPublisher;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.inquiryInterval = inquiryInterval;
    }

    /**
     * Ghi lệnh mới (NEW). Lệnh trùng do Saga gửi lại: chưa có kết quả → bỏ qua; đã có kết quả → đánh dấu publish lại
     * để Saga nhận được kết quả có thể đã bị lỡ.
     */
    @Transactional
    public void accept(InterbankTransferPayload payload) {
        Optional<OutboundTransfer> existing = outboundRepository.findByTransactionRefForUpdate(payload.transactionRef());
        if (existing.isPresent()) {
            OutboundTransfer transfer = existing.get();
            if (transfer.getStatus().isFinal()) {
                transfer.setResultPublished(false);
                log.info("Lệnh chuyển đi {} gửi lại sau khi đã có kết quả {} — publish lại kết quả",
                        transfer.getTransactionRef(), transfer.getStatus());
            }
            return;
        }
        OutboundTransfer transfer = new OutboundTransfer();
        transfer.setTransactionRef(payload.transactionRef());
        transfer.setFromAccountNumber(payload.fromAccountNumber());
        transfer.setBankCode(payload.bankCode());
        transfer.setBeneficiaryAccount(payload.beneficiaryAccount());
        transfer.setBeneficiaryName(payload.beneficiaryName());
        transfer.setAmount(payload.amount());
        transfer.setCurrency(payload.currency());
        transfer.setDescription(payload.description());
        transfer.setNextAttemptAt(LocalDateTime.now());
        outboundRepository.saveAndFlush(transfer);
        log.info("Nhận lệnh chuyển đi {} tới ngân hàng {}", payload.transactionRef(), payload.bankCode());
    }

    @Scheduled(fixedDelayString = "${bank.interbank.dispatch-interval:2s}")
    @Transactional
    public void dispatchDue() {
        List<OutboundTransfer> batch = outboundRepository.lockDueBatch(maxAttempts, batchSize);
        for (OutboundTransfer transfer : batch) {
            process(transfer);
        }
    }

    /** Nhân viên tra soát ngay một lệnh (kể cả lệnh UNKNOWN đã dừng tra soát tự động). */
    @Transactional
    public OutboundTransferResponse inquireNow(String transactionRef) {
        OutboundTransfer transfer = outboundRepository.findByTransactionRefForUpdate(transactionRef)
                .orElseThrow(() -> new ResourceNotFoundException("OUTBOUND_TRANSFER_NOT_FOUND",
                        "Không tìm thấy lệnh chuyển đi " + transactionRef));
        if (!transfer.getStatus().isFinal()) {
            apply(transfer, napasGateway.inquiry(transactionRef));
        }
        auditPublisher.publish(AuditActions.INTERBANK_INQUIRY_TRIGGERED, "TRANSACTION", transactionRef,
                "status=" + transfer.getStatus());
        return toResponse(transfer);
    }

    @Transactional(readOnly = true)
    public PageResponse<OutboundTransferResponse> list(OutboundStatusEnum status, Pageable pageable) {
        return PageResponse.from((status == null ? outboundRepository.findAll(pageable)
                : outboundRepository.findByStatus(status, pageable)).map(OutboundTransferService::toResponse));
    }

    void process(OutboundTransfer transfer) {
        NapasResult result = transfer.getStatus() == OutboundStatusEnum.NEW
                ? napasGateway.transfer(transfer)
                : napasGateway.inquiry(transfer.getTransactionRef());
        apply(transfer, result);
    }

    private void apply(OutboundTransfer transfer, NapasResult result) {
        transfer.setAttempts(transfer.getAttempts() + 1);
        if (result.napasRef() != null) {
            transfer.setNapasRef(result.napasRef());
        }
        switch (result.status()) {
            case ACCEPTED -> finish(transfer, OutboundStatusEnum.COMPLETED, null);
            case REJECTED -> finish(transfer, OutboundStatusEnum.REJECTED,
                    result.reason() == null ? "Ngân hàng nhận từ chối" : result.reason());
            case NOT_FOUND -> {
                // Napas chưa từng nhận lệnh (mất request trước khi tới Napas) → gửi lại ở lượt sau
                transfer.setStatus(OutboundStatusEnum.NEW);
                transfer.setNextAttemptAt(LocalDateTime.now());
                log.warn("Napas chưa nhận lệnh {} — sẽ gửi lại", transfer.getTransactionRef());
            }
            case PENDING -> {
                transfer.setStatus(OutboundStatusEnum.UNKNOWN);
                transfer.setReason(truncate(result.reason()));
                transfer.setNextAttemptAt(LocalDateTime.now().plus(backoff(transfer.getAttempts())));
                if (transfer.getAttempts() >= maxAttempts) {
                    log.error("Lệnh chuyển đi {} chưa rõ kết quả sau {} lần — cần tra soát thủ công",
                            transfer.getTransactionRef(), transfer.getAttempts());
                }
            }
        }
    }

    private void finish(OutboundTransfer transfer, OutboundStatusEnum status, String reason) {
        transfer.setStatus(status);
        transfer.setReason(truncate(reason));
        transfer.setCompletedAt(LocalDateTime.now());
        transfer.setResultPublished(false);
        log.info("Lệnh chuyển đi {} tới {}/{}: {}", transfer.getTransactionRef(), transfer.getBankCode(),
                MaskingUtils.maskAccountNumber(transfer.getBeneficiaryAccount()), status);
    }

    /** Giãn dần: interval × số lần, tối đa 10 phút. */
    Duration backoff(int attempts) {
        Duration delay = inquiryInterval.multipliedBy(Math.max(1, attempts));
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    private static String truncate(String reason) {
        return reason == null || reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }

    static OutboundTransferResponse toResponse(OutboundTransfer transfer) {
        return new OutboundTransferResponse(transfer.getTransactionRef(), transfer.getBankCode(),
                MaskingUtils.maskAccountNumber(transfer.getBeneficiaryAccount()), transfer.getAmount(),
                transfer.getCurrency(), transfer.getStatus(), transfer.getNapasRef(), transfer.getReason(),
                transfer.getAttempts(), transfer.getNextAttemptAt(), transfer.getCreatedAt(),
                transfer.getCompletedAt());
    }
}
