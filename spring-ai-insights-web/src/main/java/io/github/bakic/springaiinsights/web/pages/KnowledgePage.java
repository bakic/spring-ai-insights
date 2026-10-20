package io.github.bakic.springaiinsights.web.pages;

import java.util.List;

import io.github.bakic.springaiinsights.analytics.DocumentUsage;
import io.github.bakic.springaiinsights.analytics.RetrievalUsage;

/**
 * How the knowledge base is used: turns that retrieved, per-store queries, the most and least returned documents,
 * and, for catalogued stores, the documents never returned.
 */
public record KnowledgePage(long turns, long retrievalTurns, long retrievals, long emptyRetrievals,
        List<RetrievalUsage> stores, List<DocumentUsage> topDocuments, List<DocumentUsage> leastDocuments,
        List<CorpusCoverage> coverage) {

    public KnowledgePage {
        stores = List.copyOf(stores);
        topDocuments = List.copyOf(topDocuments);
        leastDocuments = List.copyOf(leastDocuments);
        coverage = List.copyOf(coverage);
    }
}
