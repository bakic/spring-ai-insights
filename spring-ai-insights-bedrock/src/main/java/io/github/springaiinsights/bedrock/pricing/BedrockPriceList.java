package io.github.springaiinsights.bedrock.pricing;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.springaiinsights.pricing.ModelPrice;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.protocols.jsoncore.JsonNode;
import software.amazon.awssdk.protocols.jsoncore.JsonNodeParser;

/** Parses Amazon Bedrock on-demand prices out of raw {@code pricing:GetProducts} JSON products (one object per price
 * dimension). Classification relies on the stable {@code feature} / {@code inferenceType} attributes rather than on
 * {@code usagetype} naming, because AWS uses two different usagetype naming schemes across models. */
public final class BedrockPriceList {

    private static final String ON_DEMAND_FEATURE = "On-demand Inference";

    private static final Set<String> IGNORED_USAGE_TYPE_MARKERS = Set.of("batch", "long_ctx", "1h", "30m", "tpm",
            "provisioned");

    private static final JsonNodeParser JSON_PARSER = JsonNodeParser.create();

    private enum Dimension {
        INPUT, OUTPUT, CACHE_READ, CACHE_WRITE
    }

    private BedrockPriceList() {
    }

    public static ParsedPrices parse(List<String> products) {
        Map<PriceKey, Map<Dimension, BigDecimal>> values = new LinkedHashMap<>();
        Set<PriceKey> ambiguous = new LinkedHashSet<>();
        for (String product : products) {
            parseProduct(product, values, ambiguous);
        }
        Map<PriceKey, ModelPrice> prices = new LinkedHashMap<>();
        for (Map.Entry<PriceKey, Map<Dimension, BigDecimal>> entry : values.entrySet()) {
            Map<Dimension, BigDecimal> dims = entry.getValue();
            BigDecimal input = dims.get(Dimension.INPUT);
            BigDecimal output = dims.get(Dimension.OUTPUT);
            if (input == null || output == null) {
                continue;
            }
            BigDecimal cacheRead = dims.getOrDefault(Dimension.CACHE_READ, BigDecimal.ZERO);
            BigDecimal cacheWrite = dims.getOrDefault(Dimension.CACHE_WRITE, BigDecimal.ZERO);
            prices.put(entry.getKey(),
                    new ModelPrice(entry.getKey().modelName(), input, output, cacheRead, cacheWrite, false));
        }
        return new ParsedPrices(Map.copyOf(prices), Set.copyOf(ambiguous));
    }

    private static void parseProduct(String product, Map<PriceKey, Map<Dimension, BigDecimal>> values,
            Set<PriceKey> ambiguous) {
        try {
            Map<String, JsonNode> root = JSON_PARSER.parse(product).asObject();
            Map<String, JsonNode> attributes = field(root, "product").flatMap(p -> field(p.asObject(), "attributes"))
                .map(JsonNode::asObject)
                .orElse(null);
            if (attributes == null) {
                return;
            }
            String feature = text(attributes, "feature");
            if (!ON_DEMAND_FEATURE.equals(feature)) {
                return;
            }
            String usageType = text(attributes, "usagetype");
            if (usageType == null) {
                return;
            }
            String usageTypeLower = usageType.toLowerCase(Locale.ROOT);
            for (String marker : IGNORED_USAGE_TYPE_MARKERS) {
                if (usageTypeLower.contains(marker)) {
                    return;
                }
            }
            Dimension dimension = dimension(text(attributes, "inferenceType"));
            if (dimension == null) {
                return;
            }
            String modelName = text(attributes, "model");
            if (modelName == null) {
                return;
            }
            BigDecimal amount = firstOnDemandPrice(root);
            if (amount == null) {
                return;
            }
            PriceScope scope = usageTypeLower.contains("global") ? PriceScope.GLOBAL : PriceScope.GEO;
            PriceKey key = new PriceKey(modelName, scope);
            record(key, dimension, amount, values, ambiguous);
        }
        catch (RuntimeException e) {
            // malformed JSON (or an unexpected shape, e.g. an HTML error page) - skip this product.
        }
    }

    private static void record(PriceKey key, Dimension dimension, BigDecimal amount,
            Map<PriceKey, Map<Dimension, BigDecimal>> values, Set<PriceKey> ambiguous) {
        if (ambiguous.contains(key)) {
            return;
        }
        Map<Dimension, BigDecimal> dims = values.computeIfAbsent(key, k -> new EnumMap<>(Dimension.class));
        BigDecimal existing = dims.get(dimension);
        if (existing != null && existing.compareTo(amount) != 0) {
            ambiguous.add(key);
            values.remove(key);
        }
        else {
            dims.put(dimension, amount);
        }
    }

    private static @Nullable BigDecimal firstOnDemandPrice(Map<String, JsonNode> root) {
        Optional<JsonNode> onDemand = field(root, "terms").flatMap(terms -> field(terms.asObject(), "OnDemand"));
        if (onDemand.isEmpty()) {
            return null;
        }
        for (JsonNode term : onDemand.get().asObject().values()) {
            Optional<JsonNode> priceDimensions = field(term.asObject(), "priceDimensions");
            if (priceDimensions.isEmpty()) {
                continue;
            }
            for (JsonNode dimension : priceDimensions.get().asObject().values()) {
                Map<String, JsonNode> dim = dimension.asObject();
                String unit = text(dim, "unit");
                BigDecimal perUnit = usdAmount(dim);
                if (unit == null || perUnit == null) {
                    continue;
                }
                if ("1M tokens".equals(unit)) {
                    return perUnit;
                }
                if ("1K tokens".equals(unit)) {
                    return perUnit.multiply(BigDecimal.valueOf(1000));
                }
            }
        }
        return null;
    }

    private static @Nullable BigDecimal usdAmount(Map<String, JsonNode> priceDimension) {
        return field(priceDimension, "pricePerUnit").flatMap(ppu -> field(ppu.asObject(), "USD"))
            .map(JsonNode::asString)
            .map(BigDecimal::new)
            .orElse(null);
    }

    private static @Nullable Dimension dimension(@Nullable String inferenceType) {
        if (inferenceType == null) {
            return null;
        }
        return switch (inferenceType) {
            case "Input tokens" -> Dimension.INPUT;
            case "Output tokens" -> Dimension.OUTPUT;
            case "Prompt cache read input tokens" -> Dimension.CACHE_READ;
            case "Prompt cache write input tokens" -> Dimension.CACHE_WRITE;
            default -> null;
        };
    }

    private static Optional<JsonNode> field(Map<String, JsonNode> object, String name) {
        return Optional.ofNullable(object.get(name));
    }

    private static @Nullable String text(Map<String, JsonNode> object, String name) {
        JsonNode node = object.get(name);
        return node == null ? null : node.text();
    }
}
