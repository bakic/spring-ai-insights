package io.github.springaiinsights.analytics;

import org.jspecify.annotations.Nullable;

/**
 * Usage of one knowledge base. {@code emptyQueries} returned no document; {@code avgTopScore} averages the best
 * score of each query that reported scores, null when none did.
 */
public record RetrievalUsage(String store, long queries, long emptyQueries, double avgResultCount,
        @Nullable Double avgTopScore) {
}
