package io.github.bakic.springaiinsights.pricing;

import java.math.BigDecimal;
import java.util.List;

import io.github.bakic.springaiinsights.analytics.ModelUsage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnpricedModelTest {

    private static ModelUsage usage(String model, long calls, long unpriced) {
        return new ModelUsage(model, calls, 0, 0, 0, 0, BigDecimal.ZERO, unpriced);
    }

    @Test
    void similarConfiguredModelIsTheLongestOneContainingOrContainedInTheReportedName() {
        List<String> configured = List.of("gpt-4o", "gpt-4o-mini", "claude");
        assertThat(UnpricedModel.similarConfigured("gpt-4o-mini-2024-07-18", configured)).isEqualTo("gpt-4o-mini");
        assertThat(UnpricedModel.similarConfigured("GPT-4O-2024", configured)).isEqualTo("gpt-4o");
        assertThat(UnpricedModel.similarConfigured("gpt", configured)).isEqualTo("gpt-4o-mini");
        assertThat(UnpricedModel.similarConfigured("llama", configured)).isNull();
        assertThat(UnpricedModel.similarConfigured("llama", List.of())).isNull();
    }

    @Test
    void onlyModelsWithUnpricedCallsAreListed() {
        List<UnpricedModel> unpriced = UnpricedModel.of(
                List.of(usage("gpt-4o", 10, 0), usage("gpt-4o-2024-08-06", 4, 4), usage("llama", 3, 1)),
                List.of("gpt-4o"));
        assertThat(unpriced).containsExactly(new UnpricedModel("gpt-4o-2024-08-06", 4, "gpt-4o"),
                new UnpricedModel("llama", 1, null));
    }
}
