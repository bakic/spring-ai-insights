package io.github.bakic.springaiinsights.bedrock.pricing;

/** Where a Bedrock on-demand price applies: through a geographic (in-region) inference profile, or through the
 * cross-region {@code global.} profile. The unqualified base model ID (no profile prefix) is {@link #GEO}: it uses
 * the same, non-{@code _Global} usage types as the geographic profiles. */
public enum PriceScope {
    GEO, GLOBAL
}
