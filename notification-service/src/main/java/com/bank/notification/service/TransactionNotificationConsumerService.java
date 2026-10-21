package com.bank.notification.service;

import com.bank.common.event.EventSerializer;
import com.bank.common.event.KafkaTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Nhận sự kiện giao dịch hoàn tất/thất bại từ transaction-service.
 */
@Service
@RequiredArgsConstructor
public class TransactionNotificationConsumerService {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopics.NOTIFICATION_EVENTS)
    public void onEvent(ConsumerRecord<String, String> record) {
        notificationService.handleTransactionEvent(EventSerializer.read(objectMapper, record.value()));
    }
}
