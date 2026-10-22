package com.bank.interbank.service;

import com.bank.common.event.EventSerializer;
import com.bank.common.event.EventTypes;
import com.bank.common.event.InterbankInboundPayload;
import com.bank.common.event.InterbankResultPayload;
import com.bank.common.event.KafkaTopics;
import com.bank.interbank.entity.InboundTransfer;
import com.bank.interbank.entity.OutboundTransfer;
import com.bank.interbank.enums.OutboundStatusEnum;
import com.bank.interbank.repository.InboundTransferRepository;
import com.bank.interbank.repository.OutboundTransferRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Outbox bằng cột trạng thái: publish lên {@code bank.interbank.events} các kết quả chuyển đi
 * ({@code RESULT_PUBLISHED = 0}) và tiền đến mới nhận ({@code EVENT_PUBLISHED = 0}), rồi đánh dấu đã publish trong
 * cùng transaction khóa dòng. At-least-once: commit lỗi sau khi gửi → gửi lại, transaction-service idempotent
 * (Saga theo bước hiện tại, tiền đến theo mã Napas). Kafka lỗi thì dừng lô, lượt sau thử lại.
 */
@Slf4j
@Service
public class InterbankEventPublisherService {

    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboundTransferRepository outboundRepository;
    private final InboundTransferRepository inboundRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    public InterbankEventPublisherService(OutboundTransferRepository outboundRepository,
                                          InboundTransferRepository inboundRepository,
                                          KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                          @Value("${bank.interbank.publish-batch-size:100}") int batchSize) {
        this.outboundRepository = outboundRepository;
        this.inboundRepository = inboundRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${bank.interbank.publish-interval:1s}")
    @Transactional
    public void publishPending() {
        try {
            for (OutboundTransfer transfer : outboundRepository.lockUnpublishedBatch(batchSize)) {
                String type = transfer.getStatus() == OutboundStatusEnum.COMPLETED
                        ? EventTypes.INTERBANK_TRANSFER_COMPLETED : EventTypes.INTERBANK_TRANSFER_REJECTED;
                send(transfer.getTransactionRef(), type, new InterbankResultPayload(transfer.getTransactionRef(),
                        transfer.getNapasRef(), transfer.getReason()));
                transfer.setResultPublished(true);
            }
            List<InboundTransfer> received = inboundRepository.lockUnpublishedBatch(batchSize);
            for (InboundTransfer inbound : received) {
                send(inbound.getNapasRef(), EventTypes.INTERBANK_INBOUND_RECEIVED, new InterbankInboundPayload(
                        inbound.getNapasRef(), inbound.getSourceBankCode(), inbound.getSourceAccount(),
                        inbound.getSourceName(), inbound.getBeneficiaryAccount(), inbound.getAmount(),
                        inbound.getCurrency(), inbound.getDescription()));
                inbound.setEventPublished(true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Các dòng đã gửi trong lô vẫn được đánh dấu khi commit; dòng lỗi giữ nguyên để lượt sau gửi lại
            log.warn("Publish event interbank thất bại: {}", e.getMessage());
        }
    }

    private void send(String key, String eventType, Object payload) throws Exception {
        kafkaTemplate.send(KafkaTopics.INTERBANK_EVENTS, key, EventSerializer.write(objectMapper, eventType, payload))
                .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
