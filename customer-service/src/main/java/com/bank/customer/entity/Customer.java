package com.bank.customer.entity;

import com.bank.customer.enums.CustomerStatusEnum;
import com.bank.customer.enums.GenderEnum;
import com.bank.customer.enums.IdTypeEnum;
import com.bank.customer.enums.KycStatusEnum;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "CUSTOMERS")
@Getter
@Setter
@NoArgsConstructor
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tham chiếu BANK_AUTH.USERS.ID; null với khách hàng chưa đăng ký online. */
    private Long userId;

    @Column(nullable = false, length = 20)
    private String customerCode;

    @Column(nullable = false, length = 150)
    private String fullName;

    @Column(nullable = false)
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private GenderEnum gender;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdTypeEnum idType;

    /** Mã hóa AES-GCM; tra cứu / unique qua {@link #idNumberHash}. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, length = 100)
    private String idNumber;

    /** Blind index của (loại giấy tờ, số giấy tờ) — {@code BlindIndexer.hashIdNumber}. */
    @Column(length = 64)
    private String idNumberHash;

    /** Mã hóa AES-GCM; tìm kiếm qua {@link #phoneHash}. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, length = 100)
    private String phone;

    @Column(length = 64)
    private String phoneHash;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 300)
    private String email;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 4000)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KycStatusEnum kycStatus = KycStatusEnum.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CustomerStatusEnum status = CustomerStatusEnum.ACTIVE;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
