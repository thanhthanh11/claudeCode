package com.bank.transaction.service;

import com.bank.common.event.EventSerializer;
import com.bank.common.tracing.TraceContextPropagator;
import com.bank.transaction.entity.OutboxEvent;
import com.bank.transaction.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi event vào bảng OUTBOX_EVENTS. Bắt buộc chạy trong transaction nghiệp vụ đang mở,
 * để event và thay đổi dữ liệu cùng commit hoặc cùng rollback.
 */
@Service
@RequiredArgsConstructor
public class OutboxService {

    private static final String AGGREGATE_TYPE = "TRANSACTION";
    static final String TRACEPARENT = "traceparent";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<TraceContextPropagator> tracePropagator;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String topic, String transactionRef, String eventType, Object payload) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType(AGGREGATE_TYPE);
        event.setAggregateId(transactionRef);
        event.setEventType(eventType);
        event.setTopic(topic);
        event.setPayload(EventSerializer.write(objectMapper, eventType, payload));
        event.setTraceContext(currentTraceparent());
        outboxEventRepository.save(event);
    }

    /**
     * Ghi lại nguyên văn một message đã có sẵn envelope (replay từ DLT): giữ nguyên eventId để consumer phía sau
     * vẫn chống xử lý trùng được. {@code key} dùng làm key Kafka.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueRaw(String aggregateType, String topic, String key, String eventType, String rawEnvelope) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType(aggregateType);
        event.setAggregateId(key);
        event.setEventType(eventType == null ? "UNKNOWN" : eventType);
        event.setTopic(topic);
        event.setPayload(rawEnvelope);
        event.setTraceContext(currentTraceparent());
        outboxEventRepository.save(event);
    }

    /** traceparent của request đang xử lý — publisher dùng để nối trace qua bước outbox (chạy trên thread khác). */
    private String currentTraceparent() {
        return tracePropagator.getIfAvailable(() -> TraceContextPropagator.NOOP).currentContext().get(TRACEPARENT);
    }
}
