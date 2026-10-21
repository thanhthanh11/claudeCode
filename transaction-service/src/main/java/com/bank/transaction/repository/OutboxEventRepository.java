package com.bank.transaction.repository;

import com.bank.transaction.entity.OutboxEvent;
import com.bank.transaction.enums.OutboxStatusEnum;
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

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Khóa một lô event NEW theo thứ tự ID; SKIP LOCKED cho phép nhiều instance publish song song không đụng nhau.
     * Không giới hạn bằng ROWNUM (Oracle đánh ROWNUM trước khi bỏ qua dòng bị khóa → instance thứ hai nhận lô rỗng)
     * và Oracle không cho FETCH FIRST cùng FOR UPDATE: mở cursor FOR UPDATE SKIP LOCKED rồi chỉ đọc {@code limit}
     * dòng — với SKIP LOCKED, Oracle khóa dòng lúc fetch. Phải gọi trong transaction.
     */
    default List<OutboxEvent> lockNextBatch(int limit) {
        try (Stream<OutboxEvent> rows = streamNewForUpdate()) {
            return rows.limit(limit).toList();
        }
    }

    @QueryHints(@QueryHint(name = AvailableHints.HINT_FETCH_SIZE, value = "100"))
    @Query(value = "SELECT * FROM OUTBOX_EVENTS WHERE STATUS = 'NEW' ORDER BY ID FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    Stream<OutboxEvent> streamNewForUpdate();

    Page<OutboxEvent> findByStatus(OutboxStatusEnum status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OutboxEvent o where o.id = :id")
    Optional<OutboxEvent> findByIdForUpdate(@Param("id") Long id);
}
