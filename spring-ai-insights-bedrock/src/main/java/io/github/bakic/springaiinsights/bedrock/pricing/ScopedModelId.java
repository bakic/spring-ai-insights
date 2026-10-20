package io.github.bakic.springaiinsights.bedrock.pricing;

/** A Bedrock base model ID (e.g. {@code anthropic.claude-sonnet-4-6}) together with the price scope implied by the
 * inference profile prefix it was invoked through (or the absence of one). */
public record ScopedModelId(String baseModelId, PriceScope scope) {
}
