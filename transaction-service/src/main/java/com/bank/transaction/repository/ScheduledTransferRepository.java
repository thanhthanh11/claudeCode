package com.bank.transaction.repository;

import com.bank.transaction.entity.ScheduledTransfer;
import com.bank.transaction.enums.ScheduledTransferStatusEnum;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ScheduledTransferRepository extends JpaRepository<ScheduledTransfer, Long> {

    List<ScheduledTransfer> findByCustomerIdOrderByIdDesc(Long customerId);

    Optional<ScheduledTransfer> findByIdAndCustomerId(Long id, Long customerId);

    @Query("select s.id from ScheduledTransfer s where s.status = :status and s.nextRunDate <= :today order by s.id")
    List<Long> findDueIds(@Param("status") ScheduledTransferStatusEnum status, @Param("today") LocalDate today);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ScheduledTransfer s where s.id = :id")
    Optional<ScheduledTransfer> findByIdForUpdate(@Param("id") Long id);
}
