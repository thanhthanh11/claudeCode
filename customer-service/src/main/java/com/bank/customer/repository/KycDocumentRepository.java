package com.bank.customer.repository;

import com.bank.customer.entity.KycDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KycDocumentRepository extends JpaRepository<KycDocument, Long> {

    List<KycDocument> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    Optional<KycDocument> findByIdAndCustomerId(Long id, Long customerId);
}
