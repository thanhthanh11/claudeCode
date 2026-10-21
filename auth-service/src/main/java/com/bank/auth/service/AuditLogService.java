package com.bank.auth.service;

import com.bank.auth.dto.AuditLogResponse;
import com.bank.auth.entity.AuditLog;
import com.bank.auth.repository.AuditLogRepository;
import com.bank.common.dto.PageResponse;
import com.bank.common.event.AuditEventPayload;
import com.bank.common.event.EventEnvelope;
import com.bank.common.event.EventSerializer;
import com.bank.common.event.KafkaTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Lưu audit từ mọi service (topic {@code bank.audit.events}) và tra cứu cho ADMIN.
 * Consumer idempotent theo eventId.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopics.AUDIT_EVENTS)
    public void onAuditEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventSerializer.read(objectMapper, record.value());
        if (auditLogRepository.existsByEventId(envelope.eventId())) {
            return;
        }
        AuditEventPayload payload = EventSerializer.payload(objectMapper, envelope, AuditEventPayload.class);
        AuditLog auditLog = new AuditLog();
        auditLog.setEventId(envelope.eventId());
        auditLog.setServiceName(payload.serviceName());
        auditLog.setActorUserId(payload.actorUserId());
        auditLog.setActorUsername(payload.actorUsername());
        auditLog.setAction(payload.action());
        auditLog.setTargetType(payload.targetType());
        auditLog.setTargetId(payload.targetId());
        auditLog.setDetail(payload.detail());
        auditLog.setOccurredAt(LocalDateTime.ofInstant(payload.occurredAt(), ZoneId.systemDefault()));
        try {
            auditLogRepository.save(auditLog);
        } catch (DataIntegrityViolationException e) {
            log.debug("Audit {} đã được lưu", envelope.eventId());
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(Long actorUserId, String action, String targetType, String targetId,
                                                 Pageable pageable) {
        return PageResponse.from(auditLogRepository.search(actorUserId, action, targetType, targetId, pageable)
                .map(AuditLogService::toResponse));
    }

    private static AuditLogResponse toResponse(AuditLog auditLog) {
        return new AuditLogResponse(auditLog.getId(), auditLog.getServiceName(), auditLog.getActorUserId(),
                auditLog.getActorUsername(), auditLog.getAction(), auditLog.getTargetType(), auditLog.getTargetId(),
                auditLog.getDetail(), auditLog.getOccurredAt());
    }
}
