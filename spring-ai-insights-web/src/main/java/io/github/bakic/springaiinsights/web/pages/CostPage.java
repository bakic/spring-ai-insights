package io.github.bakic.springaiinsights.web.pages;

import java.math.BigDecimal;
import java.util.List;

import io.github.bakic.springaiinsights.analytics.ModelUsage;
import io.github.bakic.springaiinsights.analytics.TenantUsage;
import io.github.bakic.springaiinsights.analytics.TimeBucket;
import io.github.bakic.springaiinsights.analytics.UserUsage;
import org.jspecify.annotations.Nullable;

/**
 * What the assistant costs. The month is the current calendar month in the query zone; the projection is null
 * until a full day of it has elapsed. {@code unpricedModels} are the models of the period that have no price, and
 * {@code configuredModels} the models that do, so that the page can show the mismatch.
 */
public record CostPage(String currency, BigDecimal total, @Nullable BigDecimal perConversation,
        BigDecimal monthToDate, @Nullable BigDecimal monthEndProjection, long unpricedTurns, List<TimeBucket> days,
        List<ModelUsage> models, List<TenantUsage> tenants, List<UserUsage> topUsers,
        List<UnpricedModel> unpricedModels, List<String> configuredModels) {

    public CostPage {
        days = List.copyOf(days);
        models = List.copyOf(models);
        tenants = List.copyOf(tenants);
        topUsers = List.copyOf(topUsers);
        unpricedModels = List.copyOf(unpricedModels);
        configuredModels = List.copyOf(configuredModels);
    }
}
