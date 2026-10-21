package com.bank.transaction.entity;

import com.bank.transaction.enums.DeadLetterStatusEnum;
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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Message Kafka bị đẩy vào topic {@code <topic>.DLT} sau khi consumer retry hết số lần.
 * Unique (DLT_TOPIC, PARTITION_NO, OFFSET_NO) để consumer DLT idempotent.
 */
@Entity
@Table(name = "DEAD_LETTER_EVENTS")
@Getter
@Setter
@NoArgsConstructor
public class DeadLetterEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String dltTopic;

    @Column(nullable = false, length = 150)
    private String originalTopic;

    @Column(name = "PARTITION_NO", nullable = false)
    private Integer partition;

    @Column(name = "OFFSET_NO", nullable = false)
    private Long offset;

    @Column(length = 200)
    private String messageKey;

    @Column(length = 100)
    private String eventType;

    /** Envelope JSON (chứa số tài khoản, số tiền) — mã hóa AES-GCM khi lưu. */
    @Convert(converter = EncryptedStringConverter.class)
    @Lob
    private String payload;

    @Column(length = 2000)
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeadLetterStatusEnum status = DeadLetterStatusEnum.NEW;

    @Column(length = 100)
    private String handledBy;

    private LocalDateTime handledAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
