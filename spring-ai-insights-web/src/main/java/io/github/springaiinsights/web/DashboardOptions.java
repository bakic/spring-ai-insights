package io.github.springaiinsights.web;

import java.time.Duration;
import java.util.List;

import io.github.springaiinsights.knowledge.KnowledgeCorpusCatalog;
import org.jspecify.annotations.Nullable;

/**
 * Optional dashboard settings.
 *
 * @param catalogs document catalogs, to show the documents never retrieved
 * @param abandonedAfter how long a conversation must be idle before it can count as abandoned (at least 1 minute)
 * @param traceUrl link to a trace in the tracing UI, with a {@code {traceId}} placeholder; {@code null} for none
 */
public record DashboardOptions(List<KnowledgeCorpusCatalog> catalogs, Duration abandonedAfter,
        @Nullable String traceUrl) {

    public static final DashboardOptions DEFAULTS = new DashboardOptions(List.of(), Duration.ofMinutes(30), null);

    public DashboardOptions {
        catalogs = List.copyOf(catalogs);
        if (abandonedAfter.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("abandoned-after must be at least 1 minute: " + abandonedAfter);
        }
    }
}
