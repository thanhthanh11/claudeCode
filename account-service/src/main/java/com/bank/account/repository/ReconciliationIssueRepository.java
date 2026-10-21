package com.bank.account.repository;

import com.bank.account.entity.ReconciliationIssue;
import com.bank.account.enums.ReconciliationIssueTypeEnum;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationIssueRepository extends JpaRepository<ReconciliationIssue, Long> {

    boolean existsByAccountIdAndIssueTypeAndResolvedFalse(Long accountId, ReconciliationIssueTypeEnum issueType);

    Page<ReconciliationIssue> findByResolved(Boolean resolved, Pageable pageable);
}
