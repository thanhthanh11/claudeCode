package com.bank.interbank.entity;

import com.bank.common.crypto.EncryptedStringConverter;
import com.bank.interbank.enums.OutboundStatusEnum;
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
 * Lệnh chuyển tiền ra ngân hàng khác (từ Saga của transaction-service, đã trừ tiền tài khoản nguồn).
 * {@code resultPublished = false} khi đã có kết quả cuối nhưng chưa publish về transaction-service.
 */
@Entity
@Table(name = "OUTBOUND_TRANSFERS")
@Getter
@Setter
@NoArgsConstructor
public class OutboundTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String transactionRef;

    @Column(nullable = false, length = 20)
    private String fromAccountNumber;

    @Column(nullable = false, length = 20)
    private String bankCode;

    @Column(nullable = false, length = 34)
    private String beneficiaryAccount;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 400)
    private String beneficiaryName;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboundStatusEnum status = OutboundStatusEnum.NEW;

    @Column(length = 64)
    private String napasRef;

    @Column(length = 500)
    private String reason;

    /** Số lần gửi / tra soát đã thực hiện. */
    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private LocalDateTime nextAttemptAt = LocalDateTime.now();

    @Convert(converter = NumericBooleanConverter.class)
    @Column(nullable = false)
    private Boolean resultPublished = false;

    private LocalDateTime completedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
