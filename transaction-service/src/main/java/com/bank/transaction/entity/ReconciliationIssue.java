package com.bank.transaction.entity;

import com.bank.transaction.enums.ReconciliationIssueTypeEnum;
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
import org.hibernate.type.NumericBooleanConverter;

import java.time.LocalDateTime;

/**
 * Giao dịch có bút toán sổ cái (account-service) không khớp kết quả cuối cùng.
 */
@Entity
@Table(name = "RECONCILIATION_ISSUES")
@Getter
@Setter
@NoArgsConstructor
public class ReconciliationIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String transactionRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReconciliationIssueTypeEnum issueType;

    @Column(length = 1000)
    private String detail;

    @Column(nullable = false)
    private LocalDateTime detectedAt;

    /** NUMBER(1) 0/1 — khai báo tường minh để mapping không đổi khi lên Oracle 23 (có kiểu BOOLEAN native). */
    @Convert(converter = NumericBooleanConverter.class)
    @Column(nullable = false)
    private Boolean resolved = false;

    private LocalDateTime resolvedAt;

    @Column(length = 500)
    private String note;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
