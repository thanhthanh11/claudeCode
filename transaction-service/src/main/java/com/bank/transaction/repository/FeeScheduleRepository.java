package com.bank.transaction.repository;

import com.bank.transaction.entity.FeeSchedule;
import com.bank.transaction.enums.TransactionTypeEnum;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FeeScheduleRepository extends JpaRepository<FeeSchedule, Long> {

    Optional<FeeSchedule> findByTransactionTypeAndCurrency(TransactionTypeEnum transactionType, String currency);

    List<FeeSchedule> findAllByOrderByTransactionTypeAscCurrencyAsc();
}
