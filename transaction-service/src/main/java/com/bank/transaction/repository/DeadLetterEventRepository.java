package com.bank.transaction.repository;

import com.bank.transaction.entity.DeadLetterEvent;
import com.bank.transaction.enums.DeadLetterStatusEnum;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, Long> {

    boolean existsByDltTopicAndPartitionAndOffset(String dltTopic, Integer partition, Long offset);

    Page<DeadLetterEvent> findByStatus(DeadLetterStatusEnum status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DeadLetterEvent d where d.id = :id")
    Optional<DeadLetterEvent> findByIdForUpdate(@Param("id") Long id);
}
