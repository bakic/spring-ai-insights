package io.github.bakic.springaiinsights.web.pages;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Static facts the dashboard needs once: the default application, the currency, the store kind and the trace link
 * template ({@code {traceId}} placeholder), if any.
 */
public record DashboardConfig(String application, List<String> applications, String currency, String store,
        boolean perInstance, @Nullable String traceUrl) {

    public DashboardConfig {
        applications = List.copyOf(applications);
    }
}
