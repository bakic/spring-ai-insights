package io.github.springaiinsights.bedrock.pricing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.springaiinsights.pricing.ConfiguredPriceCatalog;
import io.github.springaiinsights.pricing.ModelPrice;
import io.github.springaiinsights.pricing.PriceCatalog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.FoundationModelSummary;
import software.amazon.awssdk.services.bedrock.model.ListFoundationModelsRequest;
import software.amazon.awssdk.services.bedrock.model.ListFoundationModelsResponse;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.Filter;
import software.amazon.awssdk.services.pricing.model.FilterType;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@ExtendWith(OutputCaptureExtension.class)
class AwsPriceCatalogTest {

    private static final String REGION = "eu-west-1";

    private static List<String> fixture;

    static {
        try {
            fixture = Files.readAllLines(Path.of("src/test/resources/bedrock-pricing/eu-west-1-products.jsonl"));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final AtomicBoolean bedrockFails = new AtomicBoolean();

    private final AtomicBoolean pricingFails = new AtomicBoolean();

    private final List<FoundationModelSummary> modelSummaries = new ArrayList<>(List.of(
            model("anthropic.claude-sonnet-4-6", "Claude Sonnet 4.6"),
            model("anthropic.claude-haiku-5-5", "Claude Haiku 5.5"),
            model("anthropic.claude-opus-5", "Claude Opus 5")));

    private List<String> page1 = fixture;

    private List<String> page2 = List.of();

    private final List<GetProductsRequest> productRequests = new ArrayList<>();

    private final BedrockClient bedrock = new BedrockClient() {

        @Override
        public ListFoundationModelsResponse listFoundationModels(ListFoundationModelsRequest request) {
            if (bedrockFails.get()) {
                throw SdkClientException.create("no network");
            }
            return ListFoundationModelsResponse.builder().modelSummaries(modelSummaries).build();
        }

        @Override
        public String serviceName() {
            return "bedrock";
        }

        @Override
        public void close() {
        }
    };

    private final PricingClient pricing = new PricingClient() {

        @Override
        public GetProductsResponse getProducts(GetProductsRequest request) {
            productRequests.add(request);
            if (pricingFails.get()) {
                throw SdkClientException.create("no network");
            }
            assertThat(request.serviceCode()).isEqualTo("AmazonBedrockFoundationModels");
            assertThat(request.filters()).hasSize(1);
            Filter filter = request.filters().get(0);
            assertThat(filter.type()).isEqualTo(FilterType.TERM_MATCH);
            assertThat(filter.field()).isEqualTo("regionCode");
            assertThat(filter.value()).isEqualTo(REGION);
            if (request.nextToken() == null) {
                return GetProductsResponse.builder()
                    .priceList(page1)
                    .nextToken(page2.isEmpty() ? null : "p2")
                    .build();
            }
            return GetProductsResponse.builder().priceList(page2).build();
        }

        @Override
        public String serviceName() {
            return "pricing";
        }

        @Override
        public void close() {
        }
    };

    private static FoundationModelSummary model(String modelId, String modelName) {
        return FoundationModelSummary.builder().modelId(modelId).modelName(modelName).build();
    }

    private AwsPriceCatalog catalog(PriceCatalog fallback) {
        return new AwsPriceCatalog(() -> pricing, () -> bedrock, () -> REGION, Duration.ofHours(1), fallback,
                new SimpleMeterRegistry());
    }

    private static PriceCatalog yaml(ModelPrice... prices) {
        return new ConfiguredPriceCatalog("USD", List.of(prices));
    }

    private static ModelPrice price(String model, String amount) {
        return new ModelPrice(model, new BigDecimal(amount), new BigDecimal(amount), new BigDecimal(amount),
                new BigDecimal(amount), false);
    }

    @Test
    void awsPriceForGeoAndGlobalProfiles() {
        AwsPriceCatalog catalog = catalog(yaml());
        catalog.refresh();
        assertThat(catalog.find("eu.anthropic.claude-sonnet-4-6").orElseThrow().input())
            .isEqualByComparingTo("3.30");
        assertThat(catalog.find("global.anthropic.claude-sonnet-4-6").orElseThrow().input())
            .isEqualByComparingTo("3.00");
    }

    @Test
    void yamlPriceBeforeFirstRefresh() {
        AwsPriceCatalog catalog = catalog(yaml(price("claude-sonnet-4", "3.00")));
        assertThat(catalog.find("claude-sonnet-4").orElseThrow().input()).isEqualByComparingTo("3.00");
    }

    @Test
    void failedFirstRefreshKeepsYaml(CapturedOutput output) {
        bedrockFails.set(true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AwsPriceCatalog catalog = new AwsPriceCatalog(() -> pricing, () -> bedrock, () -> REGION, Duration.ofHours(1),
                yaml(price("claude-sonnet-4", "3.00")), registry);
        catalog.refresh();
        assertThat(catalog.find("anthropic.claude-sonnet-4-6").orElseThrow().input()).isEqualByComparingTo("3.00");
        assertThat(registry.get("ai.insights.pricing.refresh").tag("outcome", "failure").counter().count())
            .isEqualTo(1.0);
        assertThat(registry.get("ai.insights.pricing.refresh").tag("outcome", "success").counter().count())
            .isEqualTo(0.0);
    }

    @Test
    void failedLaterRefreshKeepsLastGoodPrices() {
        AwsPriceCatalog catalog = catalog(yaml());
        catalog.refresh();
        pricingFails.set(true);
        catalog.refresh();
        assertThat(catalog.find("eu.anthropic.claude-sonnet-4-6").orElseThrow().input())
            .isEqualByComparingTo("3.30");
    }

    @Test
    void applicationInferenceProfileArnUsesYaml() {
        String arn = "arn:aws:bedrock:eu-west-1:1:application-inference-profile/x";
        AwsPriceCatalog catalog = catalog(yaml(price(arn, "7.77")));
        catalog.refresh();
        assertThat(catalog.find(arn).orElseThrow().input()).isEqualByComparingTo("7.77");
    }

    @Test
    void modelMissingFromPriceListUsesYaml() {
        modelSummaries.add(model("anthropic.claude-new-9", "Claude New 9"));
        AwsPriceCatalog withYaml = catalog(yaml(price("claude-new-9", "9.99")));
        withYaml.refresh();
        assertThat(withYaml.find("anthropic.claude-new-9").orElseThrow().input()).isEqualByComparingTo("9.99");

        AwsPriceCatalog withoutYaml = catalog(yaml());
        withoutYaml.refresh();
        assertThat(withoutYaml.find("anthropic.claude-new-9")).isEmpty();
    }

    @Test
    void awsWinsOverYamlAndWarnsOnce(CapturedOutput output) {
        AwsPriceCatalog catalog = catalog(yaml(price("claude-sonnet-4", "3.00")));
        catalog.refresh();
        assertThat(catalog.find("eu.anthropic.claude-sonnet-4-6").orElseThrow().input())
            .isEqualByComparingTo("3.30");
        assertThat(catalog.find("eu.anthropic.claude-sonnet-4-6").orElseThrow().input())
            .isEqualByComparingTo("3.30");
        String warning = "ai-insights: YAML price for 'eu.anthropic.claude-sonnet-4-6' ignored, the AWS list price is used";
        assertThat(countOccurrences(output.getOut(), warning)).isEqualTo(1);
    }

    @Test
    void refreshPicksUpPriceChanges() {
        AwsPriceCatalog catalog = catalog(yaml());
        catalog.refresh();
        assertThat(catalog.find("eu.anthropic.claude-sonnet-4-6").orElseThrow().input())
            .isEqualByComparingTo("3.30");
        page1 = fixture.stream().map(line -> line.contains("EU_InputTokenCount-Units") && !line.contains("Batch")
                ? line.replace("3.3000000000", "3.6000000000") : line).toList();
        catalog.refresh();
        assertThat(catalog.find("eu.anthropic.claude-sonnet-4-6").orElseThrow().input())
            .isEqualByComparingTo("3.60");
    }

    @Test
    void paginatesBothApis() {
        List<String> sonnetLines = fixture.stream().filter(line -> line.contains("\"model\":\"Claude Sonnet 4.6\"")).toList();
        List<String> otherLines = fixture.stream().filter(line -> !line.contains("\"model\":\"Claude Sonnet 4.6\"")).toList();
        page1 = otherLines;
        page2 = sonnetLines;
        AwsPriceCatalog catalog = catalog(yaml());
        catalog.refresh();
        assertThat(productRequests).hasSize(2);
        assertThat(productRequests.get(0).nextToken()).isNull();
        assertThat(productRequests.get(1).nextToken()).isEqualTo("p2");
        assertThat(catalog.find("eu.anthropic.claude-sonnet-4-6").orElseThrow().input())
            .isEqualByComparingTo("3.30");
    }

    @Test
    void rejectsRefreshBelowOneHour() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new AwsPriceCatalog(() -> pricing, () -> bedrock, () -> REGION, Duration.ofMinutes(59),
                        yaml(), new SimpleMeterRegistry()));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
