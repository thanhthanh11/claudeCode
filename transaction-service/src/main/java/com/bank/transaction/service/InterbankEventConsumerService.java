package com.bank.transaction.service;

import com.bank.common.event.EventEnvelope;
import com.bank.common.event.EventSerializer;
import com.bank.common.event.EventTypes;
import com.bank.common.event.InterbankInboundPayload;
import com.bank.common.event.KafkaTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Nhận event từ interbank-service: tiền đến từ ngân hàng khác (tạo giao dịch INTERBANK_IN) và kết quả chuyển đi
 * (chuyển cho Saga). Cả hai idempotent: tiền đến theo mã Napas, kết quả theo bước Saga hiện tại.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterbankEventConsumerService {

    private final TransactionService transactionService;
    private final TransactionSagaService sagaService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopics.INTERBANK_EVENTS)
    public void onEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventSerializer.read(objectMapper, record.value());
        switch (envelope.eventType()) {
            case EventTypes.INTERBANK_INBOUND_RECEIVED -> transactionService.createInboundTransfer(
                    EventSerializer.payload(objectMapper, envelope, InterbankInboundPayload.class));
            case EventTypes.INTERBANK_TRANSFER_COMPLETED, EventTypes.INTERBANK_TRANSFER_REJECTED ->
                    sagaService.handle(envelope);
            default -> log.warn("Bỏ qua event interbank không hỗ trợ: {}", envelope.eventType());
        }
    }
}
