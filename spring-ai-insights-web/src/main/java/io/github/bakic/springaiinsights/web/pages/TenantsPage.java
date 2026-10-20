package io.github.bakic.springaiinsights.web.pages;

import java.util.List;

import io.github.bakic.springaiinsights.analytics.TenantUsage;

/**
 * Tenants that recorded turns in the period, busiest first, for the tenant filter. {@code truncated} is true when more
 * tenants exist than are listed.
 */
public record TenantsPage(List<TenantUsage> tenants, boolean truncated) {

    public TenantsPage {
        tenants = List.copyOf(tenants);
    }
}
