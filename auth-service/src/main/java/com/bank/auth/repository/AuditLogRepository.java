package com.bank.auth.repository;

import com.bank.auth.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    boolean existsByEventId(String eventId);

    /** Lọc tùy chọn: tham số null = không lọc theo tiêu chí đó. */
    @Query("select a from AuditLog a where (:actorUserId is null or a.actorUserId = :actorUserId) "
            + "and (:action is null or a.action = :action) "
            + "and (:targetType is null or a.targetType = :targetType) "
            + "and (:targetId is null or a.targetId = :targetId)")
    Page<AuditLog> search(@Param("actorUserId") Long actorUserId, @Param("action") String action,
                          @Param("targetType") String targetType, @Param("targetId") String targetId,
                          Pageable pageable);
}
