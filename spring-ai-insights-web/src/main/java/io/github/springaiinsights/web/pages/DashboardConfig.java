package io.github.springaiinsights.web.pages;

import java.util.List;

/** Static facts the dashboard needs once: the default application, the currency and the store kind. */
public record DashboardConfig(String application, List<String> applications, String currency, String store,
        boolean perInstance) {

    public DashboardConfig {
        applications = List.copyOf(applications);
    }
}
