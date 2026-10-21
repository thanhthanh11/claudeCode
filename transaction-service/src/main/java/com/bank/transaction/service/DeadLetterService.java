package com.bank.transaction.service;

import com.bank.common.audit.AuditActions;
import com.bank.common.audit.AuditPublisher;
import com.bank.common.dto.PageResponse;
import com.bank.common.exception.BadRequestException;
import com.bank.common.exception.ResourceNotFoundException;
import com.bank.common.security.SecurityUtils;
import com.bank.transaction.dto.DeadLetterEventResponse;
import com.bank.transaction.entity.DeadLetterEvent;
import com.bank.transaction.enums.DeadLetterStatusEnum;
import com.bank.transaction.repository.DeadLetterEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Xử lý message trong DLT: replay (đưa nguyên văn về topic gốc qua outbox, giữ eventId để consumer chống trùng)
 * hoặc bỏ qua sau khi đã xử lý thủ công.
 */
@Service
@RequiredArgsConstructor
public class DeadLetterService {

    private static final String AGGREGATE_TYPE = "DEAD_LETTER";
    private static final int MAX_AGGREGATE_ID_LENGTH = 40;

    private final DeadLetterEventRepository deadLetterRepository;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final AuditPublisher auditPublisher;

    @Transactional(readOnly = true)
    public PageResponse<DeadLetterEventResponse> list(DeadLetterStatusEnum status, Pageable pageable) {
        return PageResponse.from(deadLetterRepository.findByStatus(status, pageable).map(DeadLetterService::toResponse));
    }

    @Transactional
    public DeadLetterEventResponse replay(Long id) {
        DeadLetterEvent event = lockNew(id);
        if (event.getPayload() == null || !isJson(event.getPayload())) {
            throw new BadRequestException("DEAD_LETTER_NOT_REPLAYABLE", "Payload không phải JSON, không thể replay");
        }
        String key = event.getMessageKey() == null ? "dlt-" + event.getId() : event.getMessageKey();
        if (key.length() > MAX_AGGREGATE_ID_LENGTH) {
            throw new BadRequestException("DEAD_LETTER_NOT_REPLAYABLE", "Key message quá dài để replay qua outbox");
        }
        outboxService.enqueueRaw(AGGREGATE_TYPE, event.getOriginalTopic(), key, event.getEventType(),
                event.getPayload());
        mark(event, DeadLetterStatusEnum.REPLAYED);
        auditPublisher.publish(AuditActions.DEAD_LETTER_REPLAYED, "DEAD_LETTER", id,
                event.getOriginalTopic() + " / " + event.getEventType());
        return toResponse(event);
    }

    @Transactional
    public DeadLetterEventResponse ignore(Long id) {
        DeadLetterEvent event = lockNew(id);
        mark(event, DeadLetterStatusEnum.IGNORED);
        auditPublisher.publish(AuditActions.DEAD_LETTER_IGNORED, "DEAD_LETTER", id,
                event.getOriginalTopic() + " / " + event.getEventType());
        return toResponse(event);
    }

    private DeadLetterEvent lockNew(Long id) {
        DeadLetterEvent event = deadLetterRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("DEAD_LETTER_NOT_FOUND", "Không tìm thấy message DLT #" + id));
        if (event.getStatus() != DeadLetterStatusEnum.NEW) {
            throw new BadRequestException("DEAD_LETTER_HANDLED", "Message đã được xử lý (" + event.getStatus() + ")");
        }
        return event;
    }

    private static void mark(DeadLetterEvent event, DeadLetterStatusEnum status) {
        event.setStatus(status);
        event.setHandledBy(SecurityUtils.requireCurrentUser().username());
        event.setHandledAt(LocalDateTime.now());
    }

    private boolean isJson(String value) {
        try {
            objectMapper.readTree(value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static DeadLetterEventResponse toResponse(DeadLetterEvent event) {
        return new DeadLetterEventResponse(event.getId(), event.getDltTopic(), event.getOriginalTopic(),
                event.getPartition(), event.getOffset(), event.getMessageKey(), event.getEventType(),
                event.getErrorMessage(), event.getStatus(), event.getHandledBy(), event.getHandledAt(),
                event.getCreatedAt());
    }
}
