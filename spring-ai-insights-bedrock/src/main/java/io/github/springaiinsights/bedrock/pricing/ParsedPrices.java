package io.github.springaiinsights.bedrock.pricing;

import java.util.Map;
import java.util.Set;

import io.github.springaiinsights.pricing.ModelPrice;

/** The result of parsing an AWS Price List {@code GetProducts} response for Amazon Bedrock foundation models.
 * {@code ambiguous} lists (model, scope) pairs for which the price list carried two different values for the same
 * dimension; those are deliberately absent from {@code prices} so a caller never picks one arbitrarily. */
public record ParsedPrices(Map<PriceKey, ModelPrice> prices, Set<PriceKey> ambiguous) {
}
