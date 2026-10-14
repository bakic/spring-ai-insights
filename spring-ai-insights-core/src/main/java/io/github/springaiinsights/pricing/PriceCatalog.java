package io.github.springaiinsights.pricing;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Configured model prices. Lookup: exact match, otherwise the longest configured key contained in the model ID. */
public final class PriceCatalog {

    private final String currency;

    private final List<ModelPrice> prices;

    public PriceCatalog(String currency, List<ModelPrice> prices) {
        this.currency = currency;
        this.prices = prices.stream().sorted(Comparator.comparingInt((ModelPrice p) -> p.model().length()).reversed())
            .toList();
    }

    public String currency() {
        return currency;
    }

    public Optional<ModelPrice> find(String modelId) {
        for (ModelPrice price : prices) {
            if (price.model().equals(modelId)) {
                return Optional.of(price);
            }
        }
        return prices.stream().filter(p -> modelId.contains(p.model())).findFirst();
    }
}
