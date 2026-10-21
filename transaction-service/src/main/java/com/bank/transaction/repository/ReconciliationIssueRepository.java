package com.bank.transaction.repository;

import com.bank.transaction.entity.ReconciliationIssue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationIssueRepository extends JpaRepository<ReconciliationIssue, Long> {

    boolean existsByTransactionRefAndResolvedFalse(String transactionRef);

    Page<ReconciliationIssue> findByResolved(Boolean resolved, Pageable pageable);
}
