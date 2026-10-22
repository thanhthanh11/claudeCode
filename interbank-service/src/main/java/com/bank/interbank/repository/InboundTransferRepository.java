package com.bank.interbank.repository;

import com.bank.interbank.entity.InboundTransfer;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.hibernate.jpa.AvailableHints;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Xem ghi chú khóa theo lô ở {@link OutboundTransferRepository}. */
public interface InboundTransferRepository extends JpaRepository<InboundTransfer, Long> {

    Optional<InboundTransfer> findByNapasRef(String napasRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InboundTransfer i where i.napasRef = :napasRef")
    Optional<InboundTransfer> findByNapasRefForUpdate(@Param("napasRef") String napasRef);

    /** Tiền đến đã nhận nhưng chưa báo transaction-service cộng tiền. */
    default List<InboundTransfer> lockUnpublishedBatch(int limit) {
        try (Stream<InboundTransfer> rows = streamUnpublishedForUpdate()) {
            return rows.limit(limit).toList();
        }
    }

    @QueryHints(@QueryHint(name = AvailableHints.HINT_FETCH_SIZE, value = "100"))
    @Query(value = """
            SELECT * FROM INBOUND_TRANSFERS
             WHERE EVENT_PUBLISHED = 0 AND STATUS = 'RECEIVED'
             ORDER BY ID FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    Stream<InboundTransfer> streamUnpublishedForUpdate();

    /** Kết quả tiền đến cần báo lại Napas (lần đầu hoặc gửi lại đã đến hạn). */
    default List<InboundTransfer> lockCallbackBatch(int limit) {
        try (Stream<InboundTransfer> rows = streamCallbackDueForUpdate()) {
            return rows.limit(limit).toList();
        }
    }

    @QueryHints(@QueryHint(name = AvailableHints.HINT_FETCH_SIZE, value = "50"))
    @Query(value = """
            SELECT * FROM INBOUND_TRANSFERS
             WHERE CALLBACK_STATUS = 'PENDING' AND NEXT_CALLBACK_AT <= SYSTIMESTAMP
             ORDER BY ID FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    Stream<InboundTransfer> streamCallbackDueForUpdate();
}
