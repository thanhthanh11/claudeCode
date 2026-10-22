package com.bank.interbank.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.NumericBooleanConverter;

import java.time.LocalDateTime;

/** Ngân hàng thành viên Napas; khóa chính là BIN Napas. */
@Entity
@Table(name = "INTERBANK_BANKS")
@Getter
@Setter
@NoArgsConstructor
public class InterbankBank {

    @Id
    @Column(length = 20)
    private String bankCode;

    @Column(nullable = false, length = 50)
    private String shortName;

    @Column(nullable = false)
    private String fullName;

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
