package com.bank.account.service;

import com.bank.account.dto.BalanceOperationResponse;
import com.bank.common.event.AccountCommandPayload;
import com.bank.common.event.EventEnvelope;
import com.bank.common.event.EventSerializer;
import com.bank.common.event.EventTypes;
import com.bank.common.event.KafkaTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Nhận lệnh trừ/cộng/hoàn tiền từ Saga. Thứ tự: commit DB → gửi kết quả (đồng bộ) → ack offset.
 * Nếu crash sau commit mà chưa gửi kết quả, Kafka giao lại lệnh; nhờ kiểm tra bút toán đã tồn tại,
 * lệnh không bị áp dụng lần hai mà chỉ gửi lại kết quả.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountCommandConsumerService {

    private final AccountBalanceService balanceService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopics.ACCOUNT_COMMANDS)
    public void onCommand(ConsumerRecord<String, String> record) throws Exception {
        EventEnvelope envelope = EventSerializer.read(objectMapper, record.value());
        AccountCommandPayload command = EventSerializer.payload(objectMapper, envelope, AccountCommandPayload.class);

        BalanceOperationResponse result = switch (envelope.eventType()) {
            case EventTypes.ACCOUNT_DEBIT_REQUESTED -> balanceService.debit(command);
            case EventTypes.ACCOUNT_CREDIT_REQUESTED -> balanceService.credit(command);
            case EventTypes.ACCOUNT_REFUND_REQUESTED -> balanceService.refund(command);
            default -> {
                log.warn("Bỏ qua lệnh không hỗ trợ: {}", envelope.eventType());
                yield null;
            }
        };
        if (result == null) {
            return;
        }
        String message = EventSerializer.write(objectMapper, result.eventType(), result.payload());
        kafkaTemplate.send(KafkaTopics.TRANSACTION_EVENTS, command.transactionRef(), message).get(10, TimeUnit.SECONDS);
    }
}
