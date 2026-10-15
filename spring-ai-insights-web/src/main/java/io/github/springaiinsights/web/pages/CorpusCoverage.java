package io.github.springaiinsights.web.pages;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * How much of a store's catalogued documents were retrieved in the period.
 *
 * @param store the vector store
 * @param documents the number of catalogued documents; {@code null} while the catalog is not loaded
 * @param neverRetrieved how many catalogued documents were not retrieved
 * @param neverRetrievedSample the first of those, sorted
 */
public record CorpusCoverage(String store, @Nullable Integer documents, int neverRetrieved,
        List<String> neverRetrievedSample) {

    public CorpusCoverage {
        neverRetrievedSample = List.copyOf(neverRetrievedSample);
    }
}
