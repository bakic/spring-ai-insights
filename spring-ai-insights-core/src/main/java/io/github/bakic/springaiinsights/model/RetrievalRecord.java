package io.github.bakic.springaiinsights.model;

import java.util.List;

import org.jspecify.annotations.Nullable;

/** One vector-store / knowledge-base query within a turn. */
public record RetrievalRecord(String store, @Nullable Integer topK, int resultCount,
        List<Double> scores, List<String> documentIds, long durationMs) {

    public RetrievalRecord {
        scores = List.copyOf(scores);
        documentIds = List.copyOf(documentIds);
    }
}
