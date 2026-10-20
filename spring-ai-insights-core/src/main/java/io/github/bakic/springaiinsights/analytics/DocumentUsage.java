package io.github.bakic.springaiinsights.analytics;

/** How often a document was returned by a retrieval; {@code hits} counts retrievals that returned it. */
public record DocumentUsage(String store, String documentId, long hits) {
}
