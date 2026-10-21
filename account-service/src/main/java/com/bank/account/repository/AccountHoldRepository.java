package com.bank.account.repository;

import com.bank.account.entity.AccountHold;
import com.bank.account.enums.HoldStatusEnum;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AccountHoldRepository extends JpaRepository<AccountHold, Long> {

    List<AccountHold> findByAccountIdOrderByIdDesc(Long accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from AccountHold h where h.id = :id and h.accountId = :accountId")
    Optional<AccountHold> findByIdAndAccountIdForUpdate(@Param("id") Long id, @Param("accountId") Long accountId);

    @Query("select h.id from AccountHold h where h.status = :status and h.expiresAt < :now")
    List<Long> findExpiredIds(@Param("status") HoldStatusEnum status, @Param("now") LocalDateTime now);
}
