package io.github.bakic.springaiinsights.capture;

import io.micrometer.observation.Observation;
import org.jspecify.annotations.Nullable;

/** Finds the trace ID of the observation a turn starts in, so a turn can link to its trace. */
@FunctionalInterface
public interface TraceIdSupplier {

    /** No tracing: turns have no trace ID. */
    TraceIdSupplier NONE = current -> null;

    /** @return the trace ID, or {@code null} if there is none */
    @Nullable String traceId(Observation.@Nullable ContextView current);
}
