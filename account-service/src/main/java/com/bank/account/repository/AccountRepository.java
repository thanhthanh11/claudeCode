package com.bank.account.repository;

import com.bank.account.entity.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    List<Account> findByCustomerIdOrderByIdAsc(Long customerId);

    /** SELECT ... FOR UPDATE: khóa dòng tài khoản khi thay đổi số dư. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.accountNumber = :accountNumber")
    Optional<Account> findByAccountNumberForUpdate(@Param("accountNumber") String accountNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    /**
     * Đối soát: tài khoản có BALANCE khác tổng sổ cái hoặc AVAILABLE_BALANCE khác BALANCE - hold ACTIVE.
     * Cột trả về: ID, ACCOUNT_NUMBER, BALANCE, LEDGER_BALANCE, AVAILABLE_BALANCE, EXPECTED_AVAILABLE.
     */
    @Query(value = """
            SELECT a.ID, a.ACCOUNT_NUMBER, a.BALANCE, NVL(l.TOTAL, 0) AS LEDGER_BALANCE, a.AVAILABLE_BALANCE,
                   a.BALANCE - NVL(h.TOTAL, 0) AS EXPECTED_AVAILABLE
              FROM ACCOUNTS a
              LEFT JOIN (SELECT ACCOUNT_ID,
                                SUM(CASE ENTRY_TYPE WHEN 'CREDIT' THEN AMOUNT ELSE -AMOUNT END) AS TOTAL
                           FROM ACCOUNT_LEDGER_ENTRIES GROUP BY ACCOUNT_ID) l ON l.ACCOUNT_ID = a.ID
              LEFT JOIN (SELECT ACCOUNT_ID, SUM(AMOUNT) AS TOTAL
                           FROM ACCOUNT_HOLDS WHERE STATUS = 'ACTIVE' GROUP BY ACCOUNT_ID) h ON h.ACCOUNT_ID = a.ID
             WHERE a.BALANCE <> NVL(l.TOTAL, 0) OR a.AVAILABLE_BALANCE <> a.BALANCE - NVL(h.TOTAL, 0)""",
            nativeQuery = true)
    List<Object[]> findMismatchedBalances();
}
