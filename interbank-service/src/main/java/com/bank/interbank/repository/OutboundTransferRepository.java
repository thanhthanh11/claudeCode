package com.bank.interbank.repository;

import com.bank.interbank.entity.OutboundTransfer;
import com.bank.interbank.enums.OutboundStatusEnum;
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

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Các hàm {@code lock*Batch} mở cursor {@code FOR UPDATE SKIP LOCKED} rồi chỉ đọc {@code limit} dòng (không dùng
 * ROWNUM / FETCH FIRST — xem rule oracle-migration), để nhiều instance chạy job song song không xử lý trùng lệnh.
 * Phải gọi trong transaction.
 */
public interface OutboundTransferRepository extends JpaRepository<OutboundTransfer, Long> {

    Optional<OutboundTransfer> findByTransactionRef(String transactionRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OutboundTransfer o where o.transactionRef = :transactionRef")
    Optional<OutboundTransfer> findByTransactionRefForUpdate(@Param("transactionRef") String transactionRef);

    Page<OutboundTransfer> findByStatus(OutboundStatusEnum status, Pageable pageable);

    /** Lệnh cần gửi (NEW) hoặc cần tra soát (UNKNOWN, chưa quá số lần) đã đến hạn. */
    default List<OutboundTransfer> lockDueBatch(int maxAttempts, int limit) {
        try (Stream<OutboundTransfer> rows = streamDueForUpdate(maxAttempts)) {
            return rows.limit(limit).toList();
        }
    }

    @QueryHints(@QueryHint(name = AvailableHints.HINT_FETCH_SIZE, value = "50"))
    @Query(value = """
            SELECT * FROM OUTBOUND_TRANSFERS
             WHERE (STATUS = 'NEW' OR (STATUS = 'UNKNOWN' AND ATTEMPTS < :maxAttempts))
               AND NEXT_ATTEMPT_AT <= SYSTIMESTAMP
             ORDER BY ID FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    Stream<OutboundTransfer> streamDueForUpdate(@Param("maxAttempts") int maxAttempts);

    /** Lệnh đã có kết quả cuối nhưng chưa publish về transaction-service. */
    default List<OutboundTransfer> lockUnpublishedBatch(int limit) {
        try (Stream<OutboundTransfer> rows = streamUnpublishedForUpdate()) {
            return rows.limit(limit).toList();
        }
    }

    @QueryHints(@QueryHint(name = AvailableHints.HINT_FETCH_SIZE, value = "100"))
    @Query(value = """
            SELECT * FROM OUTBOUND_TRANSFERS
             WHERE RESULT_PUBLISHED = 0 AND STATUS IN ('COMPLETED', 'REJECTED')
             ORDER BY ID FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    Stream<OutboundTransfer> streamUnpublishedForUpdate();
}
