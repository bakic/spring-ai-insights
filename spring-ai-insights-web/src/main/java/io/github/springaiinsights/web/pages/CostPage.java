package io.github.springaiinsights.web.pages;

import java.math.BigDecimal;
import java.util.List;

import io.github.springaiinsights.analytics.ModelUsage;
import io.github.springaiinsights.analytics.TenantUsage;
import io.github.springaiinsights.analytics.TimeBucket;
import io.github.springaiinsights.analytics.UserUsage;
import org.jspecify.annotations.Nullable;

/**
 * What the assistant costs. The month is the current calendar month in the query zone; the projection is null
 * until a full day of it has elapsed.
 */
public record CostPage(String currency, BigDecimal total, @Nullable BigDecimal perConversation,
        BigDecimal monthToDate, @Nullable BigDecimal monthEndProjection, long unpricedTurns, List<TimeBucket> days,
        List<ModelUsage> models, List<TenantUsage> tenants, List<UserUsage> topUsers) {

    public CostPage {
        days = List.copyOf(days);
        models = List.copyOf(models);
        tenants = List.copyOf(tenants);
        topUsers = List.copyOf(topUsers);
    }
}
