package io.github.springaiinsights.identity;

import org.jspecify.annotations.Nullable;

/** Who a turn belongs to. */
public record InsightsIdentity(@Nullable String userId, @Nullable String tenantId) {

    public static final InsightsIdentity ANONYMOUS = new InsightsIdentity(null, null);
}
