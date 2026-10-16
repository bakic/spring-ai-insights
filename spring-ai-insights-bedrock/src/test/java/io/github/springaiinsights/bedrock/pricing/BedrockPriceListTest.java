package io.github.springaiinsights.bedrock.pricing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.springaiinsights.pricing.ModelPrice;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.springaiinsights.bedrock.pricing.PriceScope.GEO;
import static io.github.springaiinsights.bedrock.pricing.PriceScope.GLOBAL;
import static org.assertj.core.api.Assertions.assertThat;

class BedrockPriceListTest {

    private static List<String> fixture;

    private static ParsedPrices parsed;

    @BeforeAll
    static void loadFixture() throws IOException {
        Path path = Path.of("src/test/resources/bedrock-pricing/eu-west-1-products.jsonl");
        fixture = Files.readAllLines(path);
        parsed = BedrockPriceList.parse(fixture);
    }

    @Test
    void oldSchemeGeoAndGlobal() { // Claude Sonnet 4.6
        assertPrice(GEO, "Claude Sonnet 4.6", "3.30", "16.50", "0.33", "4.125");
        assertPrice(GLOBAL, "Claude Sonnet 4.6", "3.00", "15.00", "0.30", "3.75");
    }

    @Test
    void newSchemeIgnoresLongContextAndOneHourCache() { // Claude Haiku 5.5
        assertPrice(GEO, "Claude Haiku 5.5", "0.11", "0.55", "0.011", "0.1375");
        assertPrice(GLOBAL, "Claude Haiku 5.5", "0.10", "0.50", "0.01", "0.125");
    }

    @Test
    void newSchemeOpus() { // Claude Opus 5
        assertPrice(GEO, "Claude Opus 5", "5.50", "27.50", "0.55", "6.875");
        assertPrice(GLOBAL, "Claude Opus 5", "5.00", "25.00", "0.50", "6.25");
    }

    @Test
    void modelsWithoutInputAndOutputPricesAreSkipped() {
        assertThat(parsed.prices().keySet()).extracting(PriceKey::modelName).doesNotContain("Embed v4");
    }

    @Test
    void conflictingPricesMakeTheKeyAmbiguous() {
        ParsedPrices p = BedrockPriceList.parse(withConflictingSonnetGlobalInput());
        assertThat(p.prices()).doesNotContainKey(new PriceKey("Claude Sonnet 4.6", GLOBAL));
        assertThat(p.ambiguous()).containsExactly(new PriceKey("Claude Sonnet 4.6", GLOBAL));
        assertThat(p.prices()).containsKey(new PriceKey("Claude Sonnet 4.6", GEO));
    }

    @Test
    void malformedProductIsSkipped() {
        assertThat(BedrockPriceList.parse(List.of("<html>403</html>")).prices()).isEmpty();
    }

    private static List<String> withConflictingSonnetGlobalInput() {
        List<String> lines = new ArrayList<>(fixture);
        String conflicting = fixture.stream()
            .filter(line -> line.contains("3.0000000000"))
            .map(line -> line.replace("3.0000000000", "9.0000000000"))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("fixture line not found"));
        lines.add(conflicting);
        return lines;
    }

    private static void assertPrice(PriceScope scope, String modelName, String input, String output,
            String cacheRead, String cacheWrite) {
        ModelPrice price = parsed.prices().get(new PriceKey(modelName, scope));
        assertThat(price).isNotNull();
        assertThat(price.model()).isEqualTo(modelName);
        assertThat(price.input()).isEqualByComparingTo(new BigDecimal(input));
        assertThat(price.output()).isEqualByComparingTo(new BigDecimal(output));
        assertThat(price.cacheRead()).isEqualByComparingTo(new BigDecimal(cacheRead));
        assertThat(price.cacheWrite()).isEqualByComparingTo(new BigDecimal(cacheWrite));
        assertThat(price.inputIncludesCached()).isFalse();
    }
}
