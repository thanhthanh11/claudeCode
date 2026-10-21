package com.bank.transaction.entity;

import com.bank.transaction.enums.OutboxStatusEnum;
import com.bank.common.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Transactional outbox: event được ghi cùng transaction với thay đổi nghiệp vụ, sau đó
 * {@code OutboxPublisherService} đọc và publish lên Kafka.
 */
@Entity
@Table(name = "OUTBOX_EVENTS")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String aggregateType;

    /** Dùng làm key Kafka (transactionRef) để giữ thứ tự event của cùng giao dịch. */
    @Column(nullable = false, length = 40)
    private String aggregateId;

    @Column(nullable = false, length = 100)
    private String eventType;

    @Column(nullable = false, length = 150)
    private String topic;

    /** Toàn bộ EventEnvelope dạng JSON (cột CLOB có ràng buộc IS JSON). */
    /** Envelope JSON (chứa số tài khoản, số tiền) — mã hóa AES-GCM khi lưu. */
    @Convert(converter = EncryptedStringConverter.class)
    @Lob
    @Column(nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatusEnum status = OutboxStatusEnum.NEW;

    @Column(nullable = false)
    private Integer retryCount = 0;

    /** W3C traceparent lúc ghi event — publish trong span con để trace không bị đứt qua outbox. */
    @Column(length = 100)
    private String traceContext;

    private LocalDateTime publishedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
