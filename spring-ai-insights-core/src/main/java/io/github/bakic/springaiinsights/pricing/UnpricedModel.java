package io.github.bakic.springaiinsights.pricing;

import java.util.List;
import java.util.Locale;

import io.github.bakic.springaiinsights.analytics.ModelUsage;
import org.jspecify.annotations.Nullable;

/**
 * A model the assistant used that has no price, so its calls are missing from every cost. {@code model} is the name
 * the provider reported, {@code calls} how many calls used it, and {@code similar} the configured model whose name
 * looks like it (one contains the other), or {@code null}: the likely cause of the mismatch.
 */
public record UnpricedModel(String model, long calls, @Nullable String similar) {

    /** The models with unpriced calls among {@code models}, each with the configured model it resembles. */
    public static List<UnpricedModel> of(List<ModelUsage> models, List<String> configured) {
        return models.stream()
            .filter(model -> model.unpricedCalls() > 0)
            .map(model -> new UnpricedModel(model.model(), model.unpricedCalls(),
                    similarConfigured(model.model(), configured)))
            .toList();
    }

    /** The longest configured model whose name contains, or is contained in, the reported one (ignoring case). */
    public static @Nullable String similarConfigured(String reported, List<String> configured) {
        String lower = reported.toLowerCase(Locale.ROOT);
        String best = null;
        for (String candidate : configured) {
            String other = candidate.toLowerCase(Locale.ROOT);
            if ((lower.contains(other) || other.contains(lower))
                    && (best == null || candidate.length() > best.length())) {
                best = candidate;
            }
        }
        return best;
    }
}
