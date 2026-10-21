package com.bank.transaction.repository;

import com.bank.transaction.entity.Transaction;
import com.bank.transaction.enums.TransactionStatusEnum;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.hibernate.jpa.AvailableHints;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByTransactionRef(String transactionRef);

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transaction t where t.transactionRef = :ref")
    Optional<Transaction> findByTransactionRefForUpdate(@Param("ref") String transactionRef);

    /**
     * Khóa một lô giao dịch đang chờ kết quả từ account-service quá {@code before}: đang PROCESSING, hoặc đã
     * FAILED nhưng còn chờ hoàn tiền. SKIP LOCKED để nhiều instance quét song song không đụng nhau. Giới hạn số dòng
     * bằng cách chỉ đọc {@code limit} dòng từ cursor (không dùng ROWNUM — xem {@code OutboxEventRepository.lockNextBatch}).
     */
    default List<Transaction> lockStuckBatch(LocalDateTime before, int limit) {
        try (Stream<Transaction> rows = streamStuckForUpdate(before)) {
            return rows.limit(limit).toList();
        }
    }

    @QueryHints(@QueryHint(name = AvailableHints.HINT_FETCH_SIZE, value = "50"))
    @Query(value = """
            SELECT * FROM TRANSACTIONS
             WHERE (STATUS = 'PROCESSING' OR (STATUS = 'FAILED' AND SAGA_STEP = 'COMPENSATION_REQUESTED'))
               AND UPDATED_AT < :before
             ORDER BY ID
               FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    Stream<Transaction> streamStuckForUpdate(@Param("before") LocalDateTime before);

    @Query("select t from Transaction t where t.fromAccountNumber = :accountNumber or t.toAccountNumber = :accountNumber")
    Page<Transaction> findByAccountNumber(@Param("accountNumber") String accountNumber, Pageable pageable);

    Page<Transaction> findByStatus(TransactionStatusEnum status, Pageable pageable);

    /**
     * Tổng tiền và số giao dịch đi ra từ tài khoản kể từ {@code since} (tính hạn mức). Không tính giao dịch
     * FAILED / COMPENSATED vì tiền không bị trừ. Kết quả: [TOTAL, CNT].
     */
    @Query(value = """
            SELECT NVL(SUM(AMOUNT), 0), COUNT(*) FROM TRANSACTIONS
             WHERE FROM_ACCOUNT_NUMBER = :accountNumber AND TRANSACTION_TYPE = :type AND CREATED_AT >= :since
               AND STATUS NOT IN ('FAILED', 'COMPENSATED')""", nativeQuery = true)
    List<Object[]> sumOutgoingSince(@Param("accountNumber") String accountNumber, @Param("type") String type,
                                    @Param("since") LocalDateTime since);

    /** Như {@link #sumOutgoingSince} nhưng cho giao dịch đi vào tài khoản (nộp tiền). */
    @Query(value = """
            SELECT NVL(SUM(AMOUNT), 0), COUNT(*) FROM TRANSACTIONS
             WHERE TO_ACCOUNT_NUMBER = :accountNumber AND TRANSACTION_TYPE = :type AND CREATED_AT >= :since
               AND STATUS NOT IN ('FAILED', 'COMPENSATED')""", nativeQuery = true)
    List<Object[]> sumIncomingSince(@Param("accountNumber") String accountNumber, @Param("type") String type,
                                    @Param("since") LocalDateTime since);

    /** Giao dịch đã kết thúc trong khoảng [from, to) — đối soát với sổ cái. */
    @Query("select t from Transaction t where t.completedAt >= :from and t.completedAt < :to "
            + "and t.status in :statuses order by t.id")
    List<Transaction> findCompletedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                           @Param("statuses") List<TransactionStatusEnum> statuses);

    /** Giao dịch chờ xác nhận OTP tạo trước {@code before} (quá hạn). */
    @Query("select t.transactionRef from Transaction t where t.status = :status and t.createdAt < :before")
    List<String> findRefsByStatusCreatedBefore(@Param("status") TransactionStatusEnum status,
                                               @Param("before") LocalDateTime before);
}
