package io.github.bakic.springaiinsights.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.bakic.springaiinsights.metrics.InsightsMetrics;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;

/** Computes the cost of one model call from token counts and the {@link PriceCatalog}. */
public final class CostCalculator {

    private static final Log log = LogFactory.getLog(CostCalculator.class);

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    private final PriceCatalog catalog;

    private final InsightsMetrics metrics;

    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public CostCalculator(PriceCatalog catalog, InsightsMetrics metrics) {
        this.catalog = catalog;
        this.metrics = metrics;
    }

    public @Nullable BigDecimal cost(String modelId, long input, long output, long cacheRead, long cacheWrite) {
        var price = catalog.find(modelId);
        if (price.isEmpty()) {
            metrics.unpricedModel(modelId);
            if (warned.add(modelId)) {
                log.warn("ai-insights: no price configured for model '" + modelId
                        + "'; its cost is unknown. Add it to ai-insights.pricing.models");
            }
            return null;
        }
        ModelPrice p = price.get();
        long uncached = p.inputIncludesCached() ? Math.max(0, input - cacheRead - cacheWrite) : input;
        BigDecimal total = p.input().multiply(BigDecimal.valueOf(uncached))
            .add(p.output().multiply(BigDecimal.valueOf(output)))
            .add(p.cacheRead().multiply(BigDecimal.valueOf(cacheRead)))
            .add(p.cacheWrite().multiply(BigDecimal.valueOf(cacheWrite)));
        return total.divide(ONE_MILLION, 10, RoundingMode.HALF_UP);
    }

    public String currency() {
        return catalog.currency();
    }
}
