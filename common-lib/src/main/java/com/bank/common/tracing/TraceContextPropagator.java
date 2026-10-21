package com.bank.common.tracing;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Truyền trace context (W3C traceparent) qua kênh không phải HTTP/Kafka — hiện là Redis request-reply.
 * HTTP và Kafka đã được Micrometer Observation tự truyền.
 */
public interface TraceContextPropagator {

    TraceContextPropagator NOOP = new TraceContextPropagator() {
        @Override
        public Map<String, String> currentContext() {
            return Map.of();
        }

        @Override
        public <T> T runInSpan(Map<String, String> parentContext, String spanName, Supplier<T> action) {
            return action.get();
        }
    };

    /** Header trace của span hiện tại (rỗng nếu không có). */
    Map<String, String> currentContext();

    /** Chạy {@code action} trong span con của context nhận được (hoặc span gốc mới nếu context rỗng). */
    <T> T runInSpan(Map<String, String> parentContext, String spanName, Supplier<T> action);
}
