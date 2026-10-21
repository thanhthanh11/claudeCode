package com.bank.common.event;

import java.time.Instant;

/**
 * Bản ghi audit hành động của người dùng/nhân viên, gửi qua topic {@link KafkaTopics#AUDIT_EVENTS}
 * và được auth-service lưu vào BANK_AUTH.AUDIT_LOGS. {@code detail} không được chứa dữ liệu nhạy cảm.
 */
public record AuditEventPayload(
        String serviceName,
        Long actorUserId,
        String actorUsername,
        String action,
        String targetType,
        String targetId,
        String detail,
        Instant occurredAt) {
}
