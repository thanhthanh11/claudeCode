package com.bank.account.repository;

import com.bank.account.entity.AccountLedgerEntry;
import com.bank.account.enums.AccountTypeEnum;
import com.bank.account.enums.EntryTypeEnum;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AccountLedgerEntryRepository extends JpaRepository<AccountLedgerEntry, Long> {

    Optional<AccountLedgerEntry> findByAccountIdAndTransactionRefAndEntryType(Long accountId, String transactionRef,
                                                                               EntryTypeEnum entryType);

    Page<AccountLedgerEntry> findByAccountId(Long accountId, Pageable pageable);

    List<AccountLedgerEntry> findByTransactionRefOrderByIdAsc(String transactionRef);

    /** Bút toán của giao dịch trên tài khoản loại {@code accountType} (vd. bút toán thu phí trên tài khoản INTERNAL). */
    @Query("select e from AccountLedgerEntry e, Account a where a.id = e.accountId and e.transactionRef = :ref "
            + "and e.entryType = :entryType and a.accountType = :accountType order by e.id")
    List<AccountLedgerEntry> findByTransactionRefOnAccountType(@Param("ref") String transactionRef,
                                                               @Param("entryType") EntryTypeEnum entryType,
                                                               @Param("accountType") AccountTypeEnum accountType);

    /** Bút toán gần nhất trước {@code before} — số dư đầu kỳ của sao kê. */
    Optional<AccountLedgerEntry> findFirstByAccountIdAndCreatedAtBeforeOrderByIdDesc(Long accountId,
                                                                                    LocalDateTime before);

    @Query("select e from AccountLedgerEntry e where e.accountId = :accountId and e.createdAt >= :from "
            + "and e.createdAt < :to order by e.id")
    List<AccountLedgerEntry> findForStatement(@Param("accountId") Long accountId, @Param("from") LocalDateTime from,
                                              @Param("to") LocalDateTime to);
}
