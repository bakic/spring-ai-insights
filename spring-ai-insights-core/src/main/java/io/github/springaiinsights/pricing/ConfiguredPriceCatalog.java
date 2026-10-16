package io.github.springaiinsights.pricing;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * {@link PriceCatalog} backed by explicitly configured model prices.
 * Lookup: exact match, otherwise the longest configured key contained in the model ID.
 */
public final class ConfiguredPriceCatalog implements PriceCatalog {

    private final String currency;

    private final List<ModelPrice> prices;

    public ConfiguredPriceCatalog(String currency, List<ModelPrice> prices) {
        this.currency = currency;
        this.prices = prices.stream().sorted(Comparator.comparingInt((ModelPrice p) -> p.model().length()).reversed())
            .toList();
    }

    @Override
    public String currency() {
        return currency;
    }

    @Override
    public Optional<ModelPrice> find(String modelId) {
        for (ModelPrice price : prices) {
            if (price.model().equals(modelId)) {
                return Optional.of(price);
            }
        }
        return prices.stream().filter(p -> modelId.contains(p.model())).findFirst();
    }
}
