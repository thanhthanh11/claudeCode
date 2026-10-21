package com.bank.account.entity;

import com.bank.account.enums.EntryTypeEnum;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Sổ cái biến động số dư — chỉ thêm mới. Ràng buộc unique (ACCOUNT_ID, TRANSACTION_REF, ENTRY_TYPE)
 * đảm bảo mỗi giao dịch chỉ ghi một bút toán mỗi loại cho một tài khoản (chống xử lý trùng).
 */
@Entity
@Table(name = "ACCOUNT_LEDGER_ENTRIES")
@Getter
@Setter
@NoArgsConstructor
public class AccountLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long accountId;

    @Column(nullable = false, length = 40)
    private String transactionRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EntryTypeEnum entryType;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    private String description;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
