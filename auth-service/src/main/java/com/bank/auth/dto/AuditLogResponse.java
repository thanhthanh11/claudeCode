package com.bank.auth.dto;

import java.time.LocalDateTime;

public record AuditLogResponse(
        Long id,
        String serviceName,
        Long actorUserId,
        String actorUsername,
        String action,
        String targetType,
        String targetId,
        String detail,
        LocalDateTime occurredAt) {
}
