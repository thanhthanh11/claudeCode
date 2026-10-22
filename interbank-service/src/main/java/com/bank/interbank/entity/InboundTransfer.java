package com.bank.interbank.entity;

import com.bank.common.crypto.EncryptedStringConverter;
import com.bank.interbank.enums.CallbackStatusEnum;
import com.bank.interbank.enums.InboundStatusEnum;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.NumericBooleanConverter;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Tiền từ ngân hàng khác chuyển đến. {@code eventPublished = false}: chưa báo transaction-service cộng tiền;
 * {@code callbackStatus = PENDING}: đã có kết quả, chưa báo lại Napas.
 */
@Entity
@Table(name = "INBOUND_TRANSFERS")
@Getter
@Setter
@NoArgsConstructor
public class InboundTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String napasRef;

    @Column(nullable = false, length = 20)
    private String sourceBankCode;

    @Column(nullable = false, length = 34)
    private String sourceAccount;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 400)
    private String sourceName;

    @Column(nullable = false, length = 20)
    private String beneficiaryAccount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InboundStatusEnum status = InboundStatusEnum.RECEIVED;

    @Column(length = 40)
    private String transactionRef;

    @Column(length = 500)
    private String reason;

    @Convert(converter = NumericBooleanConverter.class)
    @Column(nullable = false)
    private Boolean eventPublished = false;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CallbackStatusEnum callbackStatus;

    @Column(nullable = false)
    private int callbackAttempts;

    private LocalDateTime nextCallbackAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
