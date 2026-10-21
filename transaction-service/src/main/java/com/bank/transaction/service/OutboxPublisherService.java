package com.bank.transaction.service;

import com.bank.common.tracing.TraceContextPropagator;
import com.bank.transaction.entity.OutboxEvent;
import com.bank.transaction.enums.OutboxStatusEnum;
import com.bank.transaction.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Định kỳ publish event trong outbox lên Kafka (at-least-once). Consumer phía sau phải idempotent.
 * Mỗi event được publish trong span con của trace lúc ghi outbox (TRACE_CONTEXT), nên một giao dịch
 * xem được liền mạch từ gateway tới account-service / notification-service.
 */
@Slf4j
@Service
public class OutboxPublisherService {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectProvider<TraceContextPropagator> tracePropagator;
    private final int batchSize;
    private final int maxRetries;

    public OutboxPublisherService(OutboxEventRepository outboxEventRepository,
                                  KafkaTemplate<String, String> kafkaTemplate,
                                  ObjectProvider<TraceContextPropagator> tracePropagator,
                                  @Value("${bank.outbox.batch-size:100}") int batchSize,
                                  @Value("${bank.outbox.max-retries:10}") int maxRetries) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.tracePropagator = tracePropagator;
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
    }

    @Scheduled(fixedDelayString = "${bank.outbox.poll-interval:500ms}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxEventRepository.lockNextBatch(batchSize);
        TraceContextPropagator propagator = tracePropagator.getIfAvailable(() -> TraceContextPropagator.NOOP);
        for (OutboxEvent event : batch) {
            try {
                Map<String, String> parent = event.getTraceContext() == null
                        ? Map.of() : Map.of(OutboxService.TRACEPARENT, event.getTraceContext());
                propagator.runInSpan(parent, "outbox publish " + event.getEventType(), () -> send(event));
                event.setStatus(OutboxStatusEnum.PUBLISHED);
                event.setPublishedAt(LocalDateTime.now());
            } catch (PublishInterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                int retries = event.getRetryCount() + 1;
                event.setRetryCount(retries);
                if (retries >= maxRetries) {
                    event.setStatus(OutboxStatusEnum.FAILED);
                    log.error("Outbox event #{} ({}) publish thất bại {} lần, chuyển FAILED — cần xử lý thủ công",
                            event.getId(), event.getEventType(), retries);
                } else {
                    log.warn("Publish outbox event #{} thất bại (lần {}): {}", event.getId(), retries, e.getMessage());
                }
                // Dừng lô hiện tại khi Kafka lỗi để giữ thứ tự và không chờ timeout lặp lại
                return;
            }
        }
    }

    private Object send(OutboxEvent event) {
        try {
            return kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload())
                    .get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new PublishInterruptedException();
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static final class PublishInterruptedException extends RuntimeException {
    }
}
