package com.bank.interbank.service;

import com.bank.common.exception.BusinessException;
import com.bank.interbank.entity.InboundTransfer;
import com.bank.interbank.enums.CallbackStatusEnum;
import com.bank.interbank.repository.InboundTransferRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Báo Napas kết quả tiền đến (SETTLED / RETURNED). Lỗi → gửi lại với giãn cách tăng dần; quá
 * {@code bank.interbank.max-callback-attempts} lần → FAILED, log ERROR để vận hành đối soát với Napas.
 */
@Slf4j
@Service
public class InboundCallbackService {

    private final InboundTransferRepository inboundRepository;
    private final NapasGatewayService napasGateway;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration retryInterval;

    public InboundCallbackService(InboundTransferRepository inboundRepository, NapasGatewayService napasGateway,
                                  @Value("${bank.interbank.batch-size:20}") int batchSize,
                                  @Value("${bank.interbank.max-callback-attempts:10}") int maxAttempts,
                                  @Value("${bank.interbank.inquiry-interval:30s}") Duration retryInterval) {
        this.inboundRepository = inboundRepository;
        this.napasGateway = napasGateway;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.retryInterval = retryInterval;
    }

    @Scheduled(fixedDelayString = "${bank.interbank.callback-interval:5s}")
    @Transactional
    public void sendPending() {
        List<InboundTransfer> batch = inboundRepository.lockCallbackBatch(batchSize);
        for (InboundTransfer inbound : batch) {
            send(inbound);
        }
    }

    void send(InboundTransfer inbound) {
        inbound.setCallbackAttempts(inbound.getCallbackAttempts() + 1);
        try {
            napasGateway.notifyInboundResult(inbound);
            inbound.setCallbackStatus(CallbackStatusEnum.DONE);
            inbound.setNextCallbackAt(null);
        } catch (BusinessException e) {
            if (inbound.getCallbackAttempts() >= maxAttempts) {
                inbound.setCallbackStatus(CallbackStatusEnum.FAILED);
                log.error("Báo kết quả tiền đến {} cho Napas thất bại {} lần — cần đối soát thủ công",
                        inbound.getNapasRef(), inbound.getCallbackAttempts());
            } else {
                inbound.setNextCallbackAt(LocalDateTime.now()
                        .plus(retryInterval.multipliedBy(inbound.getCallbackAttempts())));
                log.warn("Báo kết quả tiền đến {} cho Napas lỗi (lần {}): {}", inbound.getNapasRef(),
                        inbound.getCallbackAttempts(), e.getMessage());
            }
        }
    }
}
