package com.bank.transaction.repository;

import com.bank.transaction.entity.Beneficiary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BeneficiaryRepository extends JpaRepository<Beneficiary, Long> {

    List<Beneficiary> findByCustomerIdOrderByNicknameAsc(Long customerId);

    boolean existsByCustomerIdAndAccountNumber(Long customerId, String accountNumber);

    Optional<Beneficiary> findByIdAndCustomerId(Long id, Long customerId);

    long countByCustomerId(Long customerId);
}
