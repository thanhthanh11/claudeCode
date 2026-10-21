package com.bank.account.entity;

import com.bank.account.enums.HoldStatusEnum;
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
import java.time.LocalDateTime;

/**
 * Tạm giữ một phần số dư (phong tỏa theo yêu cầu, chờ đối soát...). Hold ACTIVE làm giảm AVAILABLE_BALANCE.
 */
@Entity
@Table(name = "ACCOUNT_HOLDS")
@Getter
@Setter
@NoArgsConstructor
public class AccountHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long accountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HoldStatusEnum status = HoldStatusEnum.ACTIVE;

    /** Null = giữ tới khi giải tỏa thủ công. */
    private LocalDateTime expiresAt;

    @Column(nullable = false, length = 100)
    private String createdBy;

    @Column(length = 100)
    private String releasedBy;

    private LocalDateTime releasedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
