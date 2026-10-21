package com.bank.common.audit;

import com.bank.common.event.AuditEventPayload;
import com.bank.common.event.EventSerializer;
import com.bank.common.event.EventTypes;
import com.bank.common.event.KafkaTopics;
import com.bank.common.security.AuthUser;
import com.bank.common.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

/**
 * Ghi audit hành động của người dùng/nhân viên lên Kafka; auth-service lưu vào BANK_AUTH.AUDIT_LOGS.
 * Trong transaction, event chỉ được gửi sau khi commit (thao tác bị rollback không để lại audit).
 * Gửi bất đồng bộ, lỗi Kafka chỉ ghi log — không làm hỏng nghiệp vụ chính.
 * {@code detail} không được chứa dữ liệu nhạy cảm (mật khẩu, token, OTP, số tài khoản chưa che).
 */
public class AuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(AuditPublisher.class);
    private static final int MAX_DETAIL_LENGTH = 1000;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String serviceName;

    public AuditPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper, String serviceName) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.serviceName = serviceName;
    }

    public void publish(String action, String targetType, Object targetId, String detail) {
        AuthUser actor = SecurityUtils.currentUser().orElse(null);
        AuditEventPayload payload = new AuditEventPayload(serviceName,
                actor == null ? null : actor.userId(),
                actor == null ? "system:" + serviceName : actor.username(),
                action, targetType, targetId == null ? null : String.valueOf(targetId),
                truncate(detail), Instant.now());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(payload);
                }
            });
        } else {
            send(payload);
        }
    }

    private void send(AuditEventPayload payload) {
        try {
            String message = EventSerializer.write(objectMapper, EventTypes.AUDIT_RECORDED, payload);
            kafkaTemplate.send(KafkaTopics.AUDIT_EVENTS, payload.targetType() + ":" + payload.targetId(), message)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.error("Gửi audit {} thất bại: {}", payload.action(), error.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.error("Gửi audit {} thất bại: {}", payload.action(), e.getMessage());
        }
    }

    private static String truncate(String detail) {
        if (detail == null || detail.length() <= MAX_DETAIL_LENGTH) {
            return detail;
        }
        return detail.substring(0, MAX_DETAIL_LENGTH);
    }
}
