package io.github.springaiinsights.analytics;

import java.math.BigDecimal;

/** Usage of one tenant. */
public record TenantUsage(String tenantId, long turns, long activeUsers, BigDecimal cost) {
}
