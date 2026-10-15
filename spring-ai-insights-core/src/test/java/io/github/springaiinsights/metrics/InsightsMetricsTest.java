package io.github.springaiinsights.metrics;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import io.github.springaiinsights.TestTurns;
import io.github.springaiinsights.model.ModelCall;
import io.github.springaiinsights.model.RetrievalRecord;
import io.github.springaiinsights.model.ToolCallRecord;
import io.github.springaiinsights.model.ToolOutcome;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

class InsightsMetricsTest {

    private final SimpleMeterRegistry reg = new SimpleMeterRegistry();

    private static TestTurns richTurn() {
        return TestTurns.minimal()
            .withModelCalls(List.of(new ModelCall("bedrock", "m1", 100, 20, 0, 0, 5, new BigDecimal("0.0006"), null)))
            .withToolCalls(List.of(new ToolCallRecord("search", 3, ToolOutcome.ERROR, "IOException", 0)))
            .withRetrievals(List.of(new RetrievalRecord("simple", 4, 0, List.of(), List.of(), 2)))
            .withTtftMs(150L);
    }

    @Test
    void recordsTurnToolsRetrievalsAndCost() {
        var m = new InsightsMetrics(reg, "app", false);
        m.turnRecorded(richTurn().build());
        assertThat(reg.get("ai.insights.turns").tags("application", "app", "outcome", "success", "streaming", "false").counter().count()).isEqualTo(1);
        assertThat(reg.get("ai.insights.cost").tags("model", "m1", "currency", "USD").counter().count()).isEqualTo(0.0006);
        assertThat(reg.get("ai.insights.tokens").tags("model", "m1", "type", "input").counter().count()).isEqualTo(100);
        assertThat(reg.get("ai.insights.tokens").tags("model", "m1", "type", "output").counter().count()).isEqualTo(20);
        assertThat(reg.get("ai.insights.tool.calls").tags("tool", "search", "outcome", "error").counter().count()).isEqualTo(1);
        assertThat(reg.get("ai.insights.retrievals").tags("store", "simple", "result", "empty").counter().count()).isEqualTo(1);
        assertThat(reg.get("ai.insights.ttft").timer().totalTime(MILLISECONDS)).isEqualTo(150);
        assertThat(reg.find("ai.insights.cost").tagKeys("tenant").counters()).isEmpty();
    }

    @Test
    void tenantTagOnlyWhenEnabled() {
        var m = new InsightsMetrics(reg, "app", true);
        m.turnRecorded(richTurn().withTenantId("t1").build());
        m.turnRecorded(richTurn().build());
        assertThat(reg.get("ai.insights.cost").tags("tenant", "t1").counter().count()).isEqualTo(0.0006);
        assertThat(reg.get("ai.insights.cost").tags("tenant", "none").counter().count()).isEqualTo(0.0006);
    }

    @Test
    void unpricedModelCallAddsNoCost() {
        var m = new InsightsMetrics(reg, "app", false);
        m.turnRecorded(TestTurns.minimal()
            .withModelCalls(List.of(new ModelCall("p", "m2", 1, 1, 0, 0, 1, null, null))).build());
        assertThat(reg.find("ai.insights.cost").counters()).isEmpty();
    }

    @Test
    void neverTagsUserOrConversation() {
        var m = new InsightsMetrics(reg, "app", true);
        m.turnRecorded(richTurn().withUserId("u").withConversationId("c").withTenantId("t").build());
        Set<String> forbidden = Set.of("user", "userId", "conversation", "conversationId", "turn", "turnId");
        for (Meter meter : reg.getMeters()) {
            assertThat(meter.getId().getTags()).extracting(Tag::getKey).doesNotContainAnyElementsOf(forbidden);
            assertThat(meter.getId().getTags()).extracting(Tag::getValue).doesNotContain("u", "c");
        }
    }

    @Test
    void ttftPublishesABoundedHistogram() {
        // SimpleMeterRegistry never publishes aggregable histogram buckets; Prometheus is the registry that does.
        var prometheus = new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
                io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT);
        var m = new InsightsMetrics(prometheus, "app", false);
        m.turnRecorded(TestTurns.minimal().withTtftMs(800L).build());
        var counts = prometheus.get("ai.insights.ttft").timer().takeSnapshot().histogramCounts();
        assertThat(counts).isNotEmpty();
        assertThat(counts[0].bucket(java.util.concurrent.TimeUnit.SECONDS)).isGreaterThanOrEqualTo(0.05);
        assertThat(counts[counts.length - 1].bucket(java.util.concurrent.TimeUnit.SECONDS)).isLessThanOrEqualTo(60);
        for (var c : counts) {
            double le = c.bucket(java.util.concurrent.TimeUnit.SECONDS);
            assertThat(c.count()).as("bucket le=%s", le).isEqualTo(le >= 0.8 ? 1 : 0);
        }
    }

    @Test
    void operationalCounters() {
        var m = new InsightsMetrics(reg, "app", false);
        m.eventDropped();
        m.storeError();
        m.internalError();
        m.unpricedModel("x");
        assertThat(reg.get("ai.insights.events.dropped").counter().count()).isEqualTo(1);
        assertThat(reg.get("ai.insights.store.errors").counter().count()).isEqualTo(1);
        assertThat(reg.get("ai.insights.internal.errors").counter().count()).isEqualTo(1);
        assertThat(reg.get("ai.insights.unpriced.models").tag("model", "x").counter().count()).isEqualTo(1);
    }
}
