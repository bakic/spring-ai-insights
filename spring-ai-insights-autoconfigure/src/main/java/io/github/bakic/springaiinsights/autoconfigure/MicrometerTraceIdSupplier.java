package io.github.bakic.springaiinsights.autoconfigure;

import io.github.bakic.springaiinsights.capture.TraceIdSupplier;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationView;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;
import org.jspecify.annotations.Nullable;

/**
 * Trace IDs from Micrometer Tracing. Prefers the span of the observation the turn starts in (or of its nearest
 * parent with one), because a streamed turn subscribes on a thread with no span in scope; falls back to the current
 * span.
 */
final class MicrometerTraceIdSupplier implements TraceIdSupplier {

    private final Tracer tracer;

    MicrometerTraceIdSupplier(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public @Nullable String traceId(Observation.@Nullable ContextView current) {
        Observation.ContextView context = current;
        while (context != null) {
            TracingObservationHandler.TracingContext tracing = context.get(TracingObservationHandler.TracingContext.class);
            if (tracing != null && tracing.getSpan() != null) {
                return traceId(tracing.getSpan());
            }
            ObservationView parent = context.getParentObservation();
            context = parent == null ? null : parent.getContextView();
        }
        Span span = tracer.currentSpan();
        return span == null ? null : traceId(span);
    }

    private static @Nullable String traceId(Span span) {
        String traceId = span.context().traceId();
        return traceId == null || traceId.isBlank() ? null : traceId;
    }
}
