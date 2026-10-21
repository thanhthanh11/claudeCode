package com.bank.common.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class MicrometerTraceContextPropagator implements TraceContextPropagator {

    private final Tracer tracer;
    private final Propagator propagator;

    public MicrometerTraceContextPropagator(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public Map<String, String> currentContext() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return Map.of();
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, (map, key, value) -> map.put(key, value));
        return carrier;
    }

    @Override
    public <T> T runInSpan(Map<String, String> parentContext, String spanName, Supplier<T> action) {
        Span span = parentContext == null || parentContext.isEmpty()
                ? tracer.nextSpan().name(spanName).start()
                : propagator.extract(parentContext, (Map<String, String> map, String key) -> map.get(key))
                        .name(spanName).kind(Span.Kind.SERVER).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return action.get();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
