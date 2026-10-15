package io.github.springaiinsights.observability;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.springaiinsights.feedback.Rating;
import io.github.springaiinsights.metrics.InsightsMetrics;
import io.github.springaiinsights.model.ConversationTurn;
import io.github.springaiinsights.model.ModelCall;
import io.github.springaiinsights.model.RetrievalRecord;
import io.github.springaiinsights.model.ToolCallRecord;
import io.github.springaiinsights.model.ToolOutcome;
import io.github.springaiinsights.model.TurnOutcome;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/** A Prometheus scrape produced by the real {@link InsightsMetrics}, touching every meter it can emit. */
final class SampleScrape {

    static final String APPLICATION = "shop";

    private static final Pattern METRIC = Pattern.compile("ai_insights_[a-z_]+");

    private SampleScrape() {
    }

    static PrometheusMeterRegistry registry() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        feed(registry);
        return registry;
    }

    /** Records one round of traffic covering every meter; call repeatedly to make rates non-zero. */
    static void feed(PrometheusMeterRegistry registry) {
        InsightsMetrics metrics = new InsightsMetrics(registry, APPLICATION, false);
        for (int i = 0; i < 3; i++) {
            metrics.turnRecorded(turn(TurnOutcome.SUCCESS, i == 0));
        }
        metrics.turnRecorded(turn(TurnOutcome.ERROR, false));
        metrics.turnRecorded(turn(TurnOutcome.CANCELLED, true));
        metrics.feedback(Rating.UP);
        metrics.feedback(Rating.DOWN);
        metrics.eventDropped();
        metrics.storeError();
        metrics.turnStalled();
        metrics.internalError();
        metrics.unpricedModel("mystery");
    }

    static String text() {
        return registry().scrape();
    }

    static Set<String> metricNames() {
        Set<String> names = new TreeSet<>();
        for (String line : text().split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int end = line.length();
            for (char stop : new char[] {'{', ' '}) {
                int at = line.indexOf(stop);
                if (at >= 0 && at < end) {
                    end = at;
                }
            }
            names.add(line.substring(0, end));
        }
        return names;
    }

    static Set<String> metricNamesIn(String promql) {
        Set<String> names = new TreeSet<>();
        Matcher matcher = METRIC.matcher(promql);
        while (matcher.find()) {
            names.add(matcher.group());
        }
        return names;
    }

    private static ConversationTurn turn(TurnOutcome outcome, boolean streaming) {
        boolean success = outcome == TurnOutcome.SUCCESS;
        List<ModelCall> calls = List.of(new ModelCall("openai", "gpt-4o", 1200, 300, 400, 100, 900,
                new BigDecimal("0.0123"), null));
        List<ToolCallRecord> tools = List.of(
                new ToolCallRecord("lookup", 40, success ? ToolOutcome.SUCCESS : ToolOutcome.ERROR, null, 0));
        List<RetrievalRecord> retrievals = List.of(
                new RetrievalRecord("docs", 4, success ? 4 : 0, List.of(), List.of(), 12));
        return new ConversationTurn(UUID.randomUUID().toString(), APPLICATION, "conv", false, Instant.now(),
                null, null, streaming, outcome, success ? null : "Boom", 1500L, 800L, List.of("STOP"), calls, tools,
                retrievals, new BigDecimal("0.0123"), "USD", Map.of(), null, null, List.of(), null);
    }
}
