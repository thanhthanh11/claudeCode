package com.bank.transaction.entity;

import com.bank.common.crypto.EncryptedStringConverter;
import com.bank.transaction.enums.SagaStepEnum;
import com.bank.transaction.enums.TransactionStatusEnum;
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
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "TRANSACTIONS")
@Getter
@Setter
@NoArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String transactionRef;

    /** Lưu dạng "<userId>:<Idempotency-Key>" để key của các người dùng không đụng nhau. */
    @Column(nullable = false, length = 64)
    private String idempotencyKey;

    @Column(length = 20)
    private String fromAccountNumber;

    @Column(length = 20)
    private String toAccountNumber;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal fee = BigDecimal.ZERO;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionTypeEnum transactionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatusEnum status = TransactionStatusEnum.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private SagaStepEnum sagaStep;

    private String description;

    @Column(length = 500)
    private String failureReason;

    /** Mã ngân hàng đối tác (BIN Napas) — chỉ có ở INTERBANK_OUT / INTERBANK_IN. */
    @Column(length = 20)
    private String counterpartyBankCode;

    /** Số tài khoản ở ngân hàng đối tác: người nhận (chuyển đi) hoặc người gửi (tiền đến). */
    @Column(length = 34)
    private String counterpartyAccount;

    /** Tên chủ tài khoản đối tác (mã hóa AES-GCM). */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 400)
    private String counterpartyName;

    /** Mã tham chiếu Napas. */
    @Column(length = 64)
    private String externalRef;

    /** BANK_AUTH.USERS.ID của người tạo giao dịch. */
    private Long initiatedBy;

    private LocalDateTime completedAt;

    /** Số lần gửi lại lệnh của bước Saga hiện tại do quá thời gian chờ; reset khi sang bước mới. */
    @Column(nullable = false)
    private int retryCount;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
