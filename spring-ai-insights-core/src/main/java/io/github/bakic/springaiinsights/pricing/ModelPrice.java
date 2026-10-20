package io.github.bakic.springaiinsights.pricing;

import java.math.BigDecimal;

/**
 * Prices per 1,000,000 tokens for one model. {@code model} must equal the model ID reported by the provider. {@code inputIncludesCached} is true for providers whose input token count already
 * includes cached tokens.
 */
public record ModelPrice(String model, BigDecimal input, BigDecimal output,
        BigDecimal cacheRead, BigDecimal cacheWrite, boolean inputIncludesCached) {
}
