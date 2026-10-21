package com.bank.notification.entity;

import com.bank.notification.enums.ChannelEnum;
import com.bank.notification.enums.NotificationStatusEnum;
import com.bank.common.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Unique (EVENT_ID, CHANNEL) chống gửi trùng khi Kafka giao lại message.
 */
@Entity
@Table(name = "NOTIFICATIONS")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String eventId;

    private Long customerId;

    @Column(length = 50)
    private String templateCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChannelEnum channel;

    /** Số điện thoại / email người nhận, mã hóa AES-GCM. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, length = 300)
    private String recipient;

    private String subject;

    @Column(nullable = false, length = 4000)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatusEnum status = NotificationStatusEnum.PENDING;

    @Column(nullable = false)
    private Integer retryCount = 0;

    @Column(length = 1000)
    private String errorMessage;

    private LocalDateTime sentAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
