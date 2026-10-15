package io.github.springaiinsights.web.pages;

import java.util.List;

import io.github.springaiinsights.analytics.DocumentUsage;
import io.github.springaiinsights.analytics.RetrievalUsage;

/** How the knowledge base is used: turns that retrieved, per-store queries and the most returned documents. */
public record KnowledgePage(long turns, long retrievalTurns, long retrievals, long emptyRetrievals,
        List<RetrievalUsage> stores, List<DocumentUsage> topDocuments) {

    public KnowledgePage {
        stores = List.copyOf(stores);
        topDocuments = List.copyOf(topDocuments);
    }
}
