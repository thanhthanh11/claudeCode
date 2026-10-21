package com.bank.transaction.service;

import com.bank.common.event.KafkaTopics;
import com.bank.transaction.config.KafkaDeadLetterConfig;
import com.bank.transaction.entity.DeadLetterEvent;
import com.bank.transaction.repository.DeadLetterEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Lưu mọi message bị đẩy vào DLT (của tất cả service) vào DEAD_LETTER_EVENTS để nhân viên tra cứu và replay.
 * Dùng container factory riêng: lỗi DB thì retry vô hạn (không đẩy tiếp sang .DLT.DLT, không mất message).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterConsumerService {

    private static final int MAX_ERROR_LENGTH = 2000;
    private static final int MAX_KEY_LENGTH = 200;

    private final DeadLetterEventRepository deadLetterRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = {
                    KafkaTopics.ACCOUNT_COMMANDS + KafkaTopics.DLT_SUFFIX,
                    KafkaTopics.TRANSACTION_EVENTS + KafkaTopics.DLT_SUFFIX,
                    KafkaTopics.NOTIFICATION_EVENTS + KafkaTopics.DLT_SUFFIX,
                    KafkaTopics.AUDIT_EVENTS + KafkaTopics.DLT_SUFFIX},
            groupId = "${spring.application.name}-dlt",
            containerFactory = KafkaDeadLetterConfig.DEAD_LETTER_LISTENER_FACTORY)
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        if (deadLetterRepository.existsByDltTopicAndPartitionAndOffset(record.topic(), record.partition(),
                record.offset())) {
            return;
        }
        DeadLetterEvent event = new DeadLetterEvent();
        event.setDltTopic(record.topic());
        event.setOriginalTopic(header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC,
                record.topic().replace(KafkaTopics.DLT_SUFFIX, "")));
        event.setPartition(record.partition());
        event.setOffset(record.offset());
        event.setMessageKey(truncate(record.key(), MAX_KEY_LENGTH));
        event.setPayload(record.value());
        event.setEventType(eventType(record.value()));
        event.setErrorMessage(truncate(header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE, null), MAX_ERROR_LENGTH));
        try {
            deadLetterRepository.saveAndFlush(event);
        } catch (DataIntegrityViolationException e) {
            return; // instance khác đã lưu cùng message
        }
        log.error("Message vào DLT {} (topic gốc {}, event {}) — chờ xử lý thủ công, id #{}", record.topic(),
                event.getOriginalTopic(), event.getEventType(), event.getId());
    }

    private String eventType(String value) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(value);
            JsonNode type = node.get("eventType");
            return type == null ? null : truncate(type.asText(), 100);
        } catch (Exception e) {
            return null;
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name, String defaultValue) {
        Header header = record.headers().lastHeader(name);
        return header == null ? defaultValue : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
