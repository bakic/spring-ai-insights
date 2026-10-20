package io.github.bakic.springaiinsights.report;

import java.time.Instant;

/**
 * Lets one instance among several do a job once: the first claim of a key wins until the claim expires. Stores shared
 * by several instances implement it so the weekly report is sent once.
 */
public interface ReportLock {

    /**
     * Claims a key.
     * @param key what is claimed, for example {@code weekly-report:helpdesk:2026-10-12}
     * @param expiresAt when the claim stops blocking others
     * @return {@code true} for the first claim of a key not claimed or whose claim expired, else {@code false}
     */
    boolean claim(String key, Instant expiresAt);

    /**
     * Gives a claim back, so that the next claim of the key wins. Used when the claimed job produced nothing, so that
     * another run (or instance) can retry it. Does nothing by default.
     */
    default void release(String key) {
    }
}
