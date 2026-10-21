package com.bank.notification.repository;

import com.bank.notification.entity.Notification;
import com.bank.notification.enums.ChannelEnum;
import jakarta.persistence.QueryHint;
import org.hibernate.jpa.AvailableHints;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.stream.Stream;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    boolean existsByEventIdAndChannel(String eventId, ChannelEnum channel);

    Page<Notification> findByCustomerId(Long customerId, Pageable pageable);

    /**
     * Khóa một lô thông báo FAILED còn lượt gửi lại; SKIP LOCKED để nhiều instance không gửi trùng. Chỉ đọc
     * {@code limit} dòng từ cursor FOR UPDATE (Oracle không cho FETCH FIRST cùng FOR UPDATE; ROWNUM đánh trước khi
     * bỏ qua dòng bị khóa). Phải gọi trong transaction.
     */
    default List<Notification> lockRetryBatch(int maxRetries, int limit) {
        try (Stream<Notification> rows = streamRetryableForUpdate(maxRetries)) {
            return rows.limit(limit).toList();
        }
    }

    @QueryHints(@QueryHint(name = AvailableHints.HINT_FETCH_SIZE, value = "50"))
    @Query(value = "SELECT * FROM NOTIFICATIONS WHERE STATUS = 'FAILED' AND RETRY_COUNT < :maxRetries "
            + "ORDER BY ID FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Stream<Notification> streamRetryableForUpdate(@Param("maxRetries") int maxRetries);
}
