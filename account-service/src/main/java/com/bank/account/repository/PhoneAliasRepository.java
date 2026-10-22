package com.bank.account.repository;

import com.bank.account.entity.PhoneAlias;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PhoneAliasRepository extends JpaRepository<PhoneAlias, Long> {

    Optional<PhoneAlias> findByPhoneHash(String phoneHash);

    Optional<PhoneAlias> findByAccountId(Long accountId);

    List<PhoneAlias> findByCustomerId(Long customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PhoneAlias p where p.phoneHash = :phoneHash")
    Optional<PhoneAlias> findByPhoneHashForUpdate(@Param("phoneHash") String phoneHash);
}
