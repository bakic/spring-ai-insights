package io.github.springaiinsights.pricing;

import java.math.BigDecimal;
import java.util.List;

import io.github.springaiinsights.metrics.InsightsMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CostCalculatorTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private final PriceCatalog catalog = new ConfiguredPriceCatalog("USD", List.of(
            new ModelPrice("anthropic.claude-sonnet-4", bd("3"), bd("15"), bd("0.3"), bd("3.75"), false),
            new ModelPrice("anthropic.claude", bd("1"), bd("1"), bd("0"), bd("0"), false),
            new ModelPrice("gpt-x", bd("2"), bd("8"), bd("0.5"), bd("0"), true)));

    private final SimpleMeterRegistry reg = new SimpleMeterRegistry();

    private final CostCalculator calc = new CostCalculator(catalog, new InsightsMetrics(reg, "app", false));

    @Test
    void exactThenLongestContainedMatch() {
        assertThat(catalog.find("eu.anthropic.claude-sonnet-4-20250514-v1:0")).map(ModelPrice::model).contains("anthropic.claude-sonnet-4");
        assertThat(catalog.find("anthropic.claude-haiku")).map(ModelPrice::model).contains("anthropic.claude");
        assertThat(catalog.find("gpt-x")).map(ModelPrice::model).contains("gpt-x");
        assertThat(catalog.find("mistral")).isEmpty();
    }

    @Test
    void costExcludingCached() {
        assertThat(calc.cost("anthropic.claude-sonnet-4", 1000, 500, 200, 100))
            .isEqualByComparingTo("0.0109350000");
    }

    @Test
    void costWhenInputIncludesCached() {
        assertThat(calc.cost("gpt-x", 1000, 100, 200, 0)).isEqualByComparingTo("0.0025000000");
    }

    @Test
    void cachedLargerThanInputNeverGoesNegative() {
        assertThat(calc.cost("gpt-x", 100, 0, 200, 0)).isEqualByComparingTo("0.0001000000");
    }

    @Test
    void unknownModelIsNullAndCounted() {
        assertThat(calc.cost("mistral", 10, 10, 0, 0)).isNull();
        assertThat(reg.get("ai.insights.unpriced.models").tag("model", "mistral").counter().count()).isEqualTo(1);
        assertThat(calc.currency()).isEqualTo("USD");
    }
}
