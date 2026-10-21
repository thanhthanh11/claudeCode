package com.bank.transaction.service;

import com.bank.common.event.EventSerializer;
import com.bank.common.event.KafkaTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Nhận kết quả xử lý số dư từ account-service và chuyển cho Saga.
 */
@Service
@RequiredArgsConstructor
public class TransactionEventConsumerService {

    private final TransactionSagaService sagaService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopics.TRANSACTION_EVENTS)
    public void onEvent(ConsumerRecord<String, String> record) {
        sagaService.handle(EventSerializer.read(objectMapper, record.value()));
    }
}
