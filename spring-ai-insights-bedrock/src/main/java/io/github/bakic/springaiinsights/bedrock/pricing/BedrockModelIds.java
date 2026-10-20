package io.github.bakic.springaiinsights.bedrock.pricing;

import java.util.Optional;

/** Resolves a captured Bedrock model ID (as reported by the Converse API response metadata) to a base model ID and
 * price scope, stripping an inference profile prefix such as {@code eu.} or {@code global.} if present. IDs that are
 * not plain Bedrock model/profile IDs (e.g. an application inference profile ARN) resolve to {@link Optional#empty()}
 * and must fall back to another price source. */
public final class BedrockModelIds {

    private static final String GLOBAL_PREFIX = "global";

    private BedrockModelIds() {
    }

    public static Optional<ScopedModelId> resolve(String modelId) {
        if (modelId == null || modelId.isEmpty() || modelId.startsWith("arn:")) {
            return Optional.empty();
        }
        int firstDot = modelId.indexOf('.');
        if (firstDot < 0) {
            return Optional.empty();
        }
        String firstSegment = modelId.substring(0, firstDot);
        String base = modelId;
        PriceScope scope = PriceScope.GEO;
        if (isProfilePrefix(firstSegment)) {
            String rest = modelId.substring(firstDot + 1);
            if (rest.contains(".")) {
                base = rest;
                scope = GLOBAL_PREFIX.equals(firstSegment) ? PriceScope.GLOBAL : PriceScope.GEO;
            }
        }
        return base.contains(".") ? Optional.of(new ScopedModelId(base, scope)) : Optional.empty();
    }

    private static boolean isProfilePrefix(String segment) {
        if (segment.length() < 2 || segment.length() > 6) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c < 'a' || c > 'z') {
                return false;
            }
        }
        return true;
    }
}
