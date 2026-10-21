package com.bank.transaction.entity;

import com.bank.transaction.enums.ScheduleFrequencyEnum;
import com.bank.transaction.enums.ScheduledTransferStatusEnum;
import jakarta.persistence.Column;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Lệnh chuyển tiền định kỳ. Mỗi kỳ tạo một giao dịch TRANSFER với Idempotency-Key
 * {@code sched-<id>-<yyyyMMdd>} nên chạy lại cùng kỳ không tạo giao dịch trùng.
 */
@Entity
@Table(name = "SCHEDULED_TRANSFERS")
@Getter
@Setter
@NoArgsConstructor
public class ScheduledTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long customerId;

    /** BANK_AUTH.USERS.ID của người tạo lệnh. */
    @Column(nullable = false)
    private Long createdBy;

    @Column(nullable = false, length = 20)
    private String fromAccountNumber;

    @Column(nullable = false, length = 20)
    private String toAccountNumber;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleFrequencyEnum frequency;

    @Column(nullable = false)
    private LocalDate startDate;

    private LocalDate endDate;

    @Column(nullable = false)
    private LocalDate nextRunDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduledTransferStatusEnum status = ScheduledTransferStatusEnum.ACTIVE;

    @Column(nullable = false)
    private int runCount;

    private LocalDate lastRunDate;

    @Column(length = 40)
    private String lastTransactionRef;

    @Column(length = 500)
    private String lastError;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
