package io.github.bakic.springaiinsights.pricing;

import java.util.List;
import java.util.Optional;

/** Looks up model prices by model ID. */
public interface PriceCatalog {

    String currency();

    Optional<ModelPrice> find(String modelId);

    /**
     * The model IDs this catalog was configured with, so that the dashboard can show them next to a model that has
     * no price. Empty when the catalog cannot list them.
     */
    default List<String> configuredModels() {
        return List.of();
    }
}
