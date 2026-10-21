package com.bank.transaction.repository;

import com.bank.transaction.entity.TransactionLimit;
import com.bank.transaction.enums.TransactionTypeEnum;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TransactionLimitRepository extends JpaRepository<TransactionLimit, Long> {

    Optional<TransactionLimit> findByTransactionTypeAndAccountTypeAndCurrency(TransactionTypeEnum transactionType,
                                                                              String accountType, String currency);

    Optional<TransactionLimit> findByTransactionTypeAndAccountTypeIsNullAndCurrency(TransactionTypeEnum transactionType,
                                                                                    String currency);

    List<TransactionLimit> findAllByOrderByTransactionTypeAscCurrencyAscIdAsc();

    /** Tạo dòng khóa cho tài khoản nếu chưa có (bỏ qua khi trùng khóa, kể cả khi có session khác đang chèn). */
    @Modifying
    @Query(value = "INSERT /*+ IGNORE_ROW_ON_DUPKEY_INDEX(ACCOUNT_LIMIT_LOCKS, PK_ACCOUNT_LIMIT_LOCKS) */ "
            + "INTO ACCOUNT_LIMIT_LOCKS (ACCOUNT_NUMBER) VALUES (:accountNumber)", nativeQuery = true)
    int insertLockRowIfAbsent(@Param("accountNumber") String accountNumber);

    /** Tuần tự hóa kiểm tra hạn mức của cùng một tài khoản tới khi transaction kết thúc. */
    @Query(value = "SELECT ACCOUNT_NUMBER FROM ACCOUNT_LIMIT_LOCKS WHERE ACCOUNT_NUMBER = :accountNumber FOR UPDATE",
            nativeQuery = true)
    String lockAccount(@Param("accountNumber") String accountNumber);
}
