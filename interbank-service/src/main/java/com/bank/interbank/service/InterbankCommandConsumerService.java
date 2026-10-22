package com.bank.interbank.service;

import com.bank.common.event.EventEnvelope;
import com.bank.common.event.EventSerializer;
import com.bank.common.event.EventTypes;
import com.bank.common.event.InterbankInboundResultPayload;
import com.bank.common.event.InterbankTransferPayload;
import com.bank.common.event.KafkaTopics;
import com.bank.interbank.enums.InboundStatusEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Lệnh từ transaction-service (topic {@code bank.interbank.commands}); xử lý idempotent ở
 * {@link OutboundTransferService} / {@link InboundTransferService}. Lỗi kỹ thuật để DefaultErrorHandler retry rồi
 * đẩy sang DLT.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterbankCommandConsumerService {

    private final OutboundTransferService outboundTransferService;
    private final InboundTransferService inboundTransferService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopics.INTERBANK_COMMANDS)
    public void onCommand(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventSerializer.read(objectMapper, record.value());
        switch (envelope.eventType()) {
            case EventTypes.INTERBANK_TRANSFER_REQUESTED -> acceptTransfer(
                    EventSerializer.payload(objectMapper, envelope, InterbankTransferPayload.class));
            case EventTypes.INTERBANK_INBOUND_SETTLED -> inboundTransferService.applyResult(
                    EventSerializer.payload(objectMapper, envelope, InterbankInboundResultPayload.class),
                    InboundStatusEnum.SETTLED);
            case EventTypes.INTERBANK_INBOUND_RETURNED -> inboundTransferService.applyResult(
                    EventSerializer.payload(objectMapper, envelope, InterbankInboundResultPayload.class),
                    InboundStatusEnum.RETURNED);
            default -> log.warn("Bỏ qua lệnh interbank không hỗ trợ: {}", envelope.eventType());
        }
    }

    private void acceptTransfer(InterbankTransferPayload payload) {
        try {
            outboundTransferService.accept(payload);
        } catch (DataIntegrityViolationException e) {
            // Cùng lệnh được xử lý song song (rebalance): bản kia đã ghi, unique TRANSACTION_REF chặn bản trùng
            log.info("Lệnh chuyển đi {} đã được ghi bởi tiến trình khác", payload.transactionRef());
        }
    }
}
