package io.github.bakic.springaiinsights.web.pages;

import org.jspecify.annotations.Nullable;

/**
 * A model the assistant used that has no price, so its calls are missing from every cost. {@code model} is the name
 * the provider reported, {@code calls} how many calls used it, and {@code similar} the configured model whose name
 * looks like it (one contains the other), or {@code null}: the likely cause of the mismatch.
 */
public record UnpricedModel(String model, long calls, @Nullable String similar) {
}
