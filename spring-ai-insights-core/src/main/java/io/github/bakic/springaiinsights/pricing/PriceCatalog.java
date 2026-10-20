package io.github.bakic.springaiinsights.pricing;

import java.util.Optional;

/** Looks up model prices by model ID. */
public interface PriceCatalog {

    String currency();

    Optional<ModelPrice> find(String modelId);
}
