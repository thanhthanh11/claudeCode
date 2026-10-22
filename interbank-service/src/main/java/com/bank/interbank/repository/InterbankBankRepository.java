package com.bank.interbank.repository;

import com.bank.interbank.entity.InterbankBank;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InterbankBankRepository extends JpaRepository<InterbankBank, String> {

    List<InterbankBank> findByActiveTrueOrderByShortNameAsc();
}
