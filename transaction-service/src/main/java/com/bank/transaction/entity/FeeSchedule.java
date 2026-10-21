package com.bank.transaction.entity;

import com.bank.transaction.enums.TransactionTypeEnum;
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
 * Biểu phí: phí = FIXED_FEE + AMOUNT × PERCENT_RATE, chặn trong [MIN_FEE, MAX_FEE].
 */
@Entity
@Table(name = "FEE_SCHEDULES")
@Getter
@Setter
@NoArgsConstructor
public class FeeSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionTypeEnum transactionType;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal fixedFee = BigDecimal.ZERO;

    /** Tỉ lệ dạng thập phân: 0.0002 = 0,02%. */
    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal percentRate = BigDecimal.ZERO;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal minFee = BigDecimal.ZERO;

    @Column(precision = 19, scale = 4)
    private BigDecimal maxFee;

    /** NUMBER(1) 0/1 — khai báo tường minh để mapping không đổi khi lên Oracle 23 (có kiểu BOOLEAN native). */
    @Convert(converter = NumericBooleanConverter.class)
    @Column(nullable = false)
    private Boolean active = true;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
