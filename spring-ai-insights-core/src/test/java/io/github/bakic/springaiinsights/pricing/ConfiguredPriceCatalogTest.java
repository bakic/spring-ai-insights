package io.github.bakic.springaiinsights.pricing;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredPriceCatalogTest {

    private static ModelPrice price(String model) {
        return new ModelPrice(model, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, false);
    }

    @Test
    void exactMatchWinsThenLongestContainedKey() {
        PriceCatalog catalog = new ConfiguredPriceCatalog("USD", List.of(price("gpt-4o"), price("gpt-4o-mini")));
        assertThat(catalog.find("gpt-4o-mini-2024").orElseThrow().model()).isEqualTo("gpt-4o-mini");
        assertThat(catalog.find("gpt-4o").orElseThrow().model()).isEqualTo("gpt-4o");
        assertThat(catalog.find("claude")).isEmpty();
        assertThat(catalog.currency()).isEqualTo("USD");
    }
}
