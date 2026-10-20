package io.github.bakic.springaiinsights.autoconfigure;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.TracingObservationHandler;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerTraceIdSupplierTest {

    private final SimpleTracer tracer = new SimpleTracer();

    private final ObservationRegistry registry = ObservationRegistry.create();

    private final MicrometerTraceIdSupplier supplier = new MicrometerTraceIdSupplier(tracer);

    MicrometerTraceIdSupplierTest() {
        registry.observationConfig().observationHandler(new DefaultTracingObservationHandler(tracer));
    }

    private static String traceIdOf(Observation observation) {
        TracingObservationHandler.TracingContext tracing = observation.getContext()
            .get(TracingObservationHandler.TracingContext.class);
        return tracing.getSpan().context().traceId();
    }

    @Test
    void traceIdOfObservationSpan() {
        Observation observation = Observation.start("chat", registry);
        try {
            assertThat(traceIdOf(observation)).isNotBlank();
            assertThat(supplier.traceId(observation.getContextView())).isEqualTo(traceIdOf(observation));
        }
        finally {
            observation.stop();
        }
    }

    @Test
    void parentSpanWhenChildHasNone() {
        Observation parent = Observation.start("chat", registry);
        try {
            Observation.Context child = new Observation.Context();
            child.setParentObservation(parent);
            assertThat(supplier.traceId(child)).isEqualTo(traceIdOf(parent));
        }
        finally {
            parent.stop();
        }
    }

    @Test
    void currentSpanWhenNoContext() {
        Span span = tracer.nextSpan().start();
        try (var scope = tracer.withSpan(span)) {
            assertThat(supplier.traceId(null)).isNotBlank().isEqualTo(span.context().traceId());
        }
        finally {
            span.end();
        }
    }

    @Test
    void nullWithoutAnySpan() {
        assertThat(supplier.traceId(null)).isNull();
        assertThat(supplier.traceId(new Observation.Context())).isNull();
    }
}
