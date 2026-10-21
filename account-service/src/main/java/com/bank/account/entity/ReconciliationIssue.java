package com.bank.account.entity;

import com.bank.account.enums.ReconciliationIssueTypeEnum;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Sai lệch giữa số dư lưu trên ACCOUNTS và giá trị tính lại từ sổ cái / hold.
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

    @Column(nullable = false)
    private Long accountId;

    @Column(nullable = false, length = 20)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReconciliationIssueTypeEnum issueType;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal expectedAmount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal actualAmount;

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
