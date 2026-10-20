package io.github.bakic.springaiinsights.pricing;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredPriceCatalogTest {

    private static ModelPrice price(String model) {
        return new ModelPrice(model, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, false);
    }

    @Test
    void onlyAnExactModelIdIsPriced() {
        PriceCatalog catalog = new ConfiguredPriceCatalog("USD", List.of(price("gpt-4o"), price("gpt-4o-mini")));
        assertThat(catalog.find("gpt-4o").orElseThrow().model()).isEqualTo("gpt-4o");
        assertThat(catalog.find("gpt-4o-mini").orElseThrow().model()).isEqualTo("gpt-4o-mini");
        assertThat(catalog.currency()).isEqualTo("USD");
    }

    @Test
    void aNameThatOnlyContainsOrIsContainedInAConfiguredOneIsNotPriced() {
        PriceCatalog catalog = new ConfiguredPriceCatalog("USD",
                List.of(price("gpt-4o"), price("anthropic.claude-sonnet-4")));
        assertThat(catalog.find("gpt-4o-mini")).isEmpty();
        assertThat(catalog.find("gpt-4o-2024-08-06")).isEmpty();
        assertThat(catalog.find("eu.anthropic.claude-sonnet-4")).isEmpty();
        assertThat(catalog.find("gpt-4")).isEmpty();
        assertThat(catalog.find("GPT-4O")).isEmpty();
        assertThat(catalog.find("claude")).isEmpty();
    }

    @Test
    void listsTheConfiguredModelsInOrder() {
        PriceCatalog catalog = new ConfiguredPriceCatalog("USD", List.of(price("b"), price("a")));
        assertThat(catalog.configuredModels()).containsExactly("b", "a");
    }

    @Test
    void aModelPricedTwiceIsRejected() {
        assertThatThrownBy(() -> new ConfiguredPriceCatalog("USD", List.of(price("a"), price("a"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("a");
    }
}
