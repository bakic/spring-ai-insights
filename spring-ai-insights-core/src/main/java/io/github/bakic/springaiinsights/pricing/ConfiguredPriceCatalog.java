package io.github.bakic.springaiinsights.pricing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link PriceCatalog} backed by explicitly configured model prices. A model ID is priced only when it equals a
 * configured model exactly: a name that merely contains, or is contained in, a configured one (a dated snapshot,
 * a regional prefix, a smaller variant) may be priced differently, so it is left unpriced and the dashboard says so.
 */
public final class ConfiguredPriceCatalog implements PriceCatalog {

    private final String currency;

    private final Map<String, ModelPrice> prices = new LinkedHashMap<>();

    public ConfiguredPriceCatalog(String currency, List<ModelPrice> prices) {
        this.currency = currency;
        for (ModelPrice price : prices) {
            if (this.prices.putIfAbsent(price.model(), price) != null) {
                throw new IllegalArgumentException("model " + price.model() + " is priced more than once");
            }
        }
    }

    @Override
    public String currency() {
        return currency;
    }

    @Override
    public Optional<ModelPrice> find(String modelId) {
        return Optional.ofNullable(prices.get(modelId));
    }

    @Override
    public List<String> configuredModels() {
        return List.copyOf(prices.keySet());
    }
}
