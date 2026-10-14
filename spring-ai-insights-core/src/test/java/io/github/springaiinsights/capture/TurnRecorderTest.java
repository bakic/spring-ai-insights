package io.github.springaiinsights.capture;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.github.springaiinsights.identity.InsightsIdentity;
import io.github.springaiinsights.metrics.InsightsMetrics;
import io.github.springaiinsights.model.ConversationTurn;
import io.github.springaiinsights.model.ModelCall;
import io.github.springaiinsights.model.ToolCallRecord;
import io.github.springaiinsights.model.ToolOutcome;
import io.github.springaiinsights.model.TurnOutcome;
import io.github.springaiinsights.pricing.CostCalculator;
import io.github.springaiinsights.pricing.ModelPrice;
import io.github.springaiinsights.pricing.PriceCatalog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TurnRecorderTest {

    private static final long MS = 1_000_000L;

    static CostCalculator costs() {
        var catalog = new PriceCatalog("USD", List.of(new ModelPrice("m1", new BigDecimal("1"), new BigDecimal("2"),
                BigDecimal.ZERO, BigDecimal.ZERO, false)));
        return new CostCalculator(catalog, new InsightsMetrics(new SimpleMeterRegistry(), "app", false));
    }

    static TurnRecorder recorder() {
        return new TurnRecorder("t1", "app", Instant.parse("2026-10-01T10:00:00Z"), 0L, false,
                new InsightsIdentity("u1", "acme"), "c1", false, costs());
    }

    private static ConversationTurn finish(TurnRecorder r) {
        return r.finish(TurnOutcome.SUCCESS, null, List.of("STOP"), 900 * MS).orElseThrow();
    }

    @Test
    void aggregatesCallsAndCost() {
        var r = recorder();
        r.addModelCall("bedrock", "m1", 1000, 500, 0L, 0L, 30, null);
        r.addModelCall("bedrock", "m1", 2000, 0, 0L, 0L, 40, null);
        r.putAttribute("feature", "faq");
        var turn = finish(r);
        assertThat(turn.modelCalls()).extracting(ModelCall::inputTokens).containsExactly(1000L, 2000L);
        assertThat(turn.totalCost()).isEqualByComparingTo("0.004");
        assertThat(turn.currency()).isEqualTo("USD");
        assertThat(turn.userId()).isEqualTo("u1");
        assertThat(turn.tenantId()).isEqualTo("acme");
        assertThat(turn.attributes()).containsEntry("feature", "faq");
        assertThat(turn.finishReasons()).containsExactly("STOP");
    }

    @Test
    void nullUsageCountsAsZero() {
        var r = recorder();
        r.addModelCall("bedrock", "m1", null, null, null, null, 10, null);
        var call = finish(r).modelCalls().get(0);
        assertThat(call.inputTokens()).isZero();
        assertThat(call.outputTokens()).isZero();
        assertThat(call.cacheReadTokens()).isZero();
        assertThat(call.cacheWriteTokens()).isZero();
        assertThat(call.cost()).isEqualByComparingTo("0");
    }

    @Test
    void unpricedCallMakesTotalNull() {
        var r = recorder();
        r.addModelCall("bedrock", "m1", 10, 10, null, null, 10, null);
        r.addModelCall("bedrock", "unknown", 10, 10, null, null, 10, null);
        assertThat(finish(r).totalCost()).isNull();
    }

    @Test
    void noModelCallsMeansNullCost() {
        var r = recorder();
        assertThat(r.hasModelCalls()).isFalse();
        assertThat(finish(r).totalCost()).isNull();
    }

    @Test
    void repeatIndexCountsSameTool() {
        var r = recorder();
        r.addToolCall("search", 1, null);
        r.addToolCall("search", 1, null);
        r.addToolCall("other", 1, new java.io.IOException("x"));
        var calls = finish(r).toolCalls();
        assertThat(calls).extracting(ToolCallRecord::repeatIndex).containsExactly(0, 1, 0);
        assertThat(calls.get(2).outcome()).isEqualTo(ToolOutcome.ERROR);
        assertThat(calls.get(2).errorType()).isEqualTo("IOException");
    }

    @Test
    void ttftAndLatencyFromNanos() {
        var r = recorder();
        r.markFirstToken(120 * MS);
        r.markFirstToken(300 * MS);
        var turn = finish(r);
        assertThat(turn.timeToFirstTokenMs()).isEqualTo(120L);
        assertThat(turn.latencyMs()).isEqualTo(900L);
    }

    @Test
    void errorTypeAndContent() {
        var r = recorder();
        r.setContent("hi", "hello");
        var turn = r.finish(TurnOutcome.ERROR, new IllegalStateException(), List.of(), 10 * MS).orElseThrow();
        assertThat(turn.errorType()).isEqualTo("IllegalStateException");
        assertThat(turn.userMessage()).isEqualTo("hi");
        assertThat(turn.assistantMessage()).isEqualTo("hello");
        assertThat(turn.timeToFirstTokenMs()).isNull();
    }

    @Test
    void finishIsIdempotent() {
        var r = recorder();
        assertThat(r.finish(TurnOutcome.SUCCESS, null, List.of(), 1)).isPresent();
        assertThat(r.finish(TurnOutcome.CANCELLED, null, List.of(), 2)).isEmpty();
    }

    @Test
    void outermostWinsInNestedObservations() {
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(c -> true);
        var rOuter = recorder();
        var rInner = recorder();
        var outer = Observation.start("outer", registry);
        var inner = Observation.createNotStarted("inner", registry).parentObservation(outer).start();
        TurnRecorders.attach(outer.getContext(), rOuter);
        TurnRecorders.attach(inner.getContext(), rInner);
        var leaf = Observation.createNotStarted("leaf", registry).parentObservation(inner).start();
        assertThat(TurnRecorders.outermost(leaf.getContext())).isSameAs(rOuter);
        assertThat(TurnRecorders.outermost(null)).isNull();
        var lone = Observation.start("lone", registry);
        assertThat(TurnRecorders.outermost(lone.getContext())).isNull();
    }

    @Test
    void finishWhenIdleWaitsForOpenModelCalls() {
        var r = recorder();
        var sink = new java.util.ArrayList<ConversationTurn>();
        r.beginModelCall();
        r.finishWhenIdle(TurnOutcome.SUCCESS, null, List.of(), 10 * MS, rec -> { }, sink::add);
        assertThat(sink).isEmpty();
        r.addModelCall("p", "m1", 5, 5, null, null, 1, null);
        r.endModelCall();
        assertThat(sink).singleElement().satisfies(t -> assertThat(t.modelCalls()).hasSize(1));
        r.endModelCall();
        assertThat(sink).hasSize(1);
    }

    @Test
    void finishWhenIdleRunsBeforeFinishThenSinkImmediatelyWhenIdle() {
        var r = recorder();
        var sink = new java.util.ArrayList<ConversationTurn>();
        r.finishWhenIdle(TurnOutcome.SUCCESS, null, List.of(), 10 * MS,
                rec -> rec.addModelCall("unknown", "m1", 1, 1, null, null, 0, null), sink::add);
        assertThat(sink).singleElement().satisfies(t -> assertThat(t.modelCalls()).hasSize(1));
    }
}
