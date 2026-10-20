package io.github.bakic.springaiinsights.metrics;

import java.time.Duration;
import java.util.Locale;

import io.github.bakic.springaiinsights.feedback.Rating;
import io.github.bakic.springaiinsights.model.ConversationTurn;
import io.github.bakic.springaiinsights.model.ModelCall;
import io.github.bakic.springaiinsights.model.RetrievalRecord;
import io.github.bakic.springaiinsights.model.ToolCallRecord;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

/**
 * Low-cardinality Micrometer meters derived from recorded turns. User, conversation and turn IDs are never
 * used as tags; the tenant is a tag only when explicitly enabled.
 */
public final class InsightsMetrics {

    private final MeterRegistry registry;

    private final Tags base;

    private final boolean tenantTag;

    public InsightsMetrics(MeterRegistry registry, String application, boolean tenantTag) {
        this.registry = registry;
        this.base = Tags.of("application", application);
        this.tenantTag = tenantTag;
        // Registered up front: a counter born at 1 has no earlier sample, so rate() and increase() would miss it.
        for (String name : new String[] {"ai.insights.events.dropped", "ai.insights.store.errors",
                "ai.insights.turns.stalled", "ai.insights.internal.errors"}) {
            registry.counter(name, base);
        }
    }

    public void turnRecorded(ConversationTurn turn) {
        registry.counter("ai.insights.turns", base.and("outcome", lower(turn.outcome().name()),
                "streaming", String.valueOf(turn.streaming()))).increment();
        Tags costTags = tenantTag ? base.and("tenant", turn.tenantId() == null ? "none" : turn.tenantId()) : base;
        for (ModelCall call : turn.modelCalls()) {
            Tags model = base.and("model", call.model());
            registry.counter("ai.insights.tokens", model.and("type", "input")).increment(call.inputTokens());
            registry.counter("ai.insights.tokens", model.and("type", "output")).increment(call.outputTokens());
            registry.counter("ai.insights.tokens", model.and("type", "cache_read")).increment(call.cacheReadTokens());
            registry.counter("ai.insights.tokens", model.and("type", "cache_write")).increment(call.cacheWriteTokens());
            if (call.cost() != null) {
                registry.counter("ai.insights.cost", costTags.and("model", call.model(), "currency", turn.currency()))
                    .increment(call.cost().doubleValue());
            }
        }
        for (ToolCallRecord tool : turn.toolCalls()) {
            registry.counter("ai.insights.tool.calls", base.and("tool", tool.name(), "outcome",
                    lower(tool.outcome().name()))).increment();
        }
        for (RetrievalRecord retrieval : turn.retrievals()) {
            registry.counter("ai.insights.retrievals", base.and("store", retrieval.store(), "result",
                    retrieval.resultCount() == 0 ? "empty" : "hit")).increment();
        }
        if (turn.timeToFirstTokenMs() != null) {
            Timer.builder("ai.insights.ttft")
                .tags(base)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(50))
                .maximumExpectedValue(Duration.ofSeconds(60))
                .register(registry)
                .record(Duration.ofMillis(turn.timeToFirstTokenMs()));
        }
    }

    public void feedback(Rating rating) {
        registry.counter("ai.insights.feedback", base.and("rating", lower(rating.name()))).increment();
    }

    public void eventDropped() {
        registry.counter("ai.insights.events.dropped", base).increment();
    }

    public void storeError() {
        registry.counter("ai.insights.store.errors", base).increment();
    }

    /** A turn was finished by the stall timeout because a model call never reported back. */
    public void turnStalled() {
        registry.counter("ai.insights.turns.stalled", base).increment();
    }

    public void internalError() {
        registry.counter("ai.insights.internal.errors", base).increment();
    }

    public void unpricedModel(String model) {
        registry.counter("ai.insights.unpriced.models", base.and("model", model)).increment();
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
