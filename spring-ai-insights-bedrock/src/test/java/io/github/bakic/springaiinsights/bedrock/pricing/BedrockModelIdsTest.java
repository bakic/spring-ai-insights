package io.github.bakic.springaiinsights.bedrock.pricing;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class BedrockModelIdsTest {

    @ParameterizedTest
    @CsvSource({
            "eu.anthropic.claude-sonnet-4-6, anthropic.claude-sonnet-4-6, GEO",
            "global.anthropic.claude-sonnet-4-6, anthropic.claude-sonnet-4-6, GLOBAL",
            "us.anthropic.claude-sonnet-4-5-20250929-v1:0, anthropic.claude-sonnet-4-5-20250929-v1:0, GEO",
            "apac.anthropic.claude-haiku-5-5, anthropic.claude-haiku-5-5, GEO",
            "anthropic.claude-opus-5, anthropic.claude-opus-5, GEO" })
    void resolvesProfilePrefix(String id, String base, PriceScope scope) {
        assertThat(BedrockModelIds.resolve(id)).contains(new ScopedModelId(base, scope));
    }

    @ParameterizedTest
    @ValueSource(strings = { "arn:aws:bedrock:eu-west-1:123456789012:application-inference-profile/abc", "gpt-4o", "" })
    void notABedrockModelId(String id) {
        assertThat(BedrockModelIds.resolve(id)).isEmpty();
    }
}
