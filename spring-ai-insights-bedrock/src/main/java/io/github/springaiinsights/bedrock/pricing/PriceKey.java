package io.github.springaiinsights.bedrock.pricing;

/** A model name and the price scope ({@link PriceScope#GEO} or {@link PriceScope#GLOBAL}) it was priced for. */
public record PriceKey(String modelName, PriceScope scope) {
}
