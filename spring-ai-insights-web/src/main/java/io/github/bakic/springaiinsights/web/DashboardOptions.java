package io.github.bakic.springaiinsights.web;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import io.github.bakic.springaiinsights.knowledge.KnowledgeCorpusCatalog;
import org.jspecify.annotations.Nullable;

/**
 * Optional dashboard settings.
 *
 * @param catalogs document catalogs, to show the documents never retrieved
 * @param abandonedAfter how long a conversation must be idle before it can count as abandoned (at least 1 minute)
 * @param traceUrl link to a trace in the tracing UI, with a {@code {traceId}} placeholder; {@code null} for none
 * @param configuredModels the models that have a configured price, shown next to a model that has none
 */
public record DashboardOptions(List<KnowledgeCorpusCatalog> catalogs, Duration abandonedAfter,
        @Nullable String traceUrl, List<String> configuredModels) {

    public static final DashboardOptions DEFAULTS = new DashboardOptions(List.of(), Duration.ofMinutes(30), null);

    private static final Pattern TRACE_URL = Pattern.compile("^https?://[^\\s\"'<>]+$");

    public DashboardOptions(List<KnowledgeCorpusCatalog> catalogs, Duration abandonedAfter,
            @Nullable String traceUrl) {
        this(catalogs, abandonedAfter, traceUrl, List.of());
    }

    public DashboardOptions {
        catalogs = List.copyOf(catalogs);
        configuredModels = List.copyOf(configuredModels);
        if (abandonedAfter.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("abandoned-after must be at least 1 minute: " + abandonedAfter);
        }
        if (traceUrl != null && (!TRACE_URL.matcher(traceUrl).matches() || !traceUrl.contains("{traceId}"))) {
            throw new IllegalArgumentException("trace URL must be an http(s) URL containing {traceId}: " + traceUrl);
        }
    }
}
