package com.bank.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Audit hành động của người dùng/nhân viên trên mọi service (chỉ thêm mới). Unique EVENT_ID chống ghi trùng.
 */
@Entity
@Table(name = "AUDIT_LOGS")
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String eventId;

    @Column(nullable = false, length = 50)
    private String serviceName;

    private Long actorUserId;

    @Column(nullable = false, length = 100)
    private String actorUsername;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(length = 50)
    private String targetType;

    @Column(length = 100)
    private String targetId;

    @Column(length = 1000)
    private String detail;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
