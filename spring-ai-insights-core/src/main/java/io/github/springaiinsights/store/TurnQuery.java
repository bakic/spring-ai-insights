package io.github.springaiinsights.store;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/** Turns of one application started in [{@code from}, {@code to}), optionally for one tenant. */
public record TurnQuery(String application, Instant from, Instant to, @Nullable String tenantId, int limit) {
}
