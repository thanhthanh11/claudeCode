package com.bank.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Alias số điện thoại → tài khoản nhận. Chỉ lưu blind index của số điện thoại; số điện thoại gốc nằm ở hồ sơ
 * khách hàng (customer-service) và được so lại mỗi lần phân giải.
 */
@Entity
@Table(name = "PHONE_ALIASES")
@Getter
@Setter
@NoArgsConstructor
public class PhoneAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String phoneHash;

    @Column(nullable = false)
    private Long accountId;

    @Column(nullable = false)
    private Long customerId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
