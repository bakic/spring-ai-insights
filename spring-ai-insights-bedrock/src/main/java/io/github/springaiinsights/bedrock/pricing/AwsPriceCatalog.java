package io.github.springaiinsights.bedrock.pricing;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.github.springaiinsights.pricing.ModelPrice;
import io.github.springaiinsights.pricing.PriceCatalog;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.FoundationModelSummary;
import software.amazon.awssdk.services.bedrock.model.ListFoundationModelsRequest;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.Filter;
import software.amazon.awssdk.services.pricing.model.FilterType;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;

/**
 * {@link PriceCatalog} built from the AWS Price List API ({@code pricing:GetProducts},
 * {@code ServiceCode=AmazonBedrockFoundationModels}) and refreshed periodically, with a YAML-backed
 * {@link PriceCatalog} as fallback for models AWS does not price, before the first successful load, and
 * while AWS is unreachable. Lookups read an immutable in-memory snapshot - no I/O on the chat path. Needs
 * {@code bedrock:ListFoundationModels} and {@code pricing:GetProducts}.
 */
public final class AwsPriceCatalog implements PriceCatalog, AutoCloseable {

    private static final String SERVICE_CODE = "AmazonBedrockFoundationModels";

    private static final Log logger = LogFactory.getLog(AwsPriceCatalog.class);

    /** Delay before the first retry after a failed refresh; doubles on each consecutive failure thereafter,
     * capped at {@code refresh}. */
    private static final Duration INITIAL_BACKOFF = Duration.ofMinutes(5);

    private record Snapshot(Map<String, String> modelNames, Map<PriceKey, ModelPrice> prices) {
    }

    private final Supplier<PricingClient> pricingSupplier;

    private final Supplier<BedrockClient> bedrockSupplier;

    private final Supplier<String> regionSupplier;

    private final Duration refresh;

    private final PriceCatalog fallback;

    private final MeterRegistry registry;

    /** Model IDs for which the YAML-vs-AWS price mismatch has already been warned about, once. */
    private final Set<String> yamlOverrideWarned = ConcurrentHashMap.newKeySet();

    private volatile @Nullable Snapshot snapshot;

    private @Nullable ScheduledExecutorService scheduler;

    /** Backoff to use for the next retry after a failure; reset to {@link #INITIAL_BACKOFF} on success. */
    private Duration nextBackoff = INITIAL_BACKOFF;

    public AwsPriceCatalog(Supplier<PricingClient> pricing, Supplier<BedrockClient> bedrock,
            Supplier<String> region, Duration refresh, PriceCatalog fallback, MeterRegistry registry) {
        if (refresh.compareTo(Duration.ofHours(1)) < 0) {
            throw new IllegalArgumentException("refresh must be at least 1 hour: " + refresh);
        }
        this.pricingSupplier = pricing;
        this.bedrockSupplier = bedrock;
        this.regionSupplier = region;
        this.refresh = refresh;
        this.fallback = fallback;
        this.registry = registry;
        // Registered up front: a counter born at 1 has no earlier sample, so rate() and increase() would miss it.
        registry.counter("ai.insights.pricing.refresh", "outcome", "success");
        registry.counter("ai.insights.pricing.refresh", "outcome", "failure");
    }

    @Override
    public String currency() {
        return "USD";
    }

    @Override
    public Optional<ModelPrice> find(String modelId) {
        Snapshot current = snapshot;
        if (current != null) {
            Optional<ModelPrice> awsPrice = BedrockModelIds.resolve(modelId)
                .flatMap(scoped -> awsPrice(current, scoped));
            if (awsPrice.isPresent()) {
                warnIfYamlDiffers(modelId, awsPrice.get());
                return awsPrice;
            }
        }
        return fallback.find(modelId);
    }

    private Optional<ModelPrice> awsPrice(Snapshot snapshot, ScopedModelId scoped) {
        String modelName = snapshot.modelNames().get(scoped.baseModelId());
        if (modelName == null) {
            return Optional.empty();
        }
        ModelPrice price = snapshot.prices().get(new PriceKey(modelName, scoped.scope()));
        if (price == null) {
            return Optional.empty();
        }
        return Optional.of(new ModelPrice(scoped.baseModelId(), price.input(), price.output(), price.cacheRead(),
                price.cacheWrite(), price.inputIncludesCached()));
    }

    private void warnIfYamlDiffers(String modelId, ModelPrice awsPrice) {
        fallback.find(modelId).ifPresent(yamlPrice -> {
            boolean differs = yamlPrice.input().compareTo(awsPrice.input()) != 0
                    || yamlPrice.output().compareTo(awsPrice.output()) != 0
                    || yamlPrice.cacheRead().compareTo(awsPrice.cacheRead()) != 0
                    || yamlPrice.cacheWrite().compareTo(awsPrice.cacheWrite()) != 0;
            if (differs && yamlOverrideWarned.add(modelId)) {
                logger.warn("ai-insights: YAML price for '" + modelId + "' ignored, the AWS list price is used");
            }
        });
    }

    /** Refreshes the snapshot now, synchronously. Never throws: a failure is logged and counted, and the
     * previous snapshot (or none) is kept; the suppliers are called again at the next refresh. Returns
     * whether the refresh succeeded, so the caller can schedule the next attempt accordingly. */
    synchronized boolean refresh() {
        try {
            String region = regionSupplier.get();
            Map<String, String> modelNames = new LinkedHashMap<>();
            for (FoundationModelSummary summary : bedrockSupplier.get()
                .listFoundationModels(ListFoundationModelsRequest.builder().build())
                .modelSummaries()) {
                modelNames.put(summary.modelId(), summary.modelName());
            }
            List<String> products = listPrices(region);
            ParsedPrices parsed = BedrockPriceList.parse(products);
            for (PriceKey ambiguous : parsed.ambiguous()) {
                logger.warn("ai-insights: ambiguous Amazon Bedrock list price for " + ambiguous.modelName() + " ("
                        + ambiguous.scope() + "), ignored");
            }
            snapshot = new Snapshot(Map.copyOf(modelNames), parsed.prices());
            long pricedModels = parsed.prices().keySet().stream().map(PriceKey::modelName).distinct().count();
            logger.info("ai-insights: loaded Amazon Bedrock list prices for " + pricedModels + " models in "
                    + region);
            registry.counter("ai.insights.pricing.refresh", "outcome", "success").increment();
            return true;
        }
        catch (Exception | LinkageError e) {
            logger.warn("ai-insights: Amazon Bedrock price list refresh failed: " + e);
            logger.debug("Refresh failed", e);
            registry.counter("ai.insights.pricing.refresh", "outcome", "failure").increment();
            return false;
        }
    }

    private List<String> listPrices(String region) {
        PricingClient pricing = pricingSupplier.get();
        List<String> products = new ArrayList<>();
        String nextToken = null;
        do {
            GetProductsResponse page = pricing.getProducts(GetProductsRequest.builder()
                .serviceCode(SERVICE_CODE)
                .filters(Filter.builder().type(FilterType.TERM_MATCH).field("regionCode").value(region).build())
                .nextToken(nextToken)
                .build());
            products.addAll(page.priceList());
            nextToken = page.nextToken();
        }
        while (nextToken != null);
        return products;
    }

    /** Refreshes now, then self-reschedules: after {@code refresh} on success, or after a backoff starting
     * at {@link #INITIAL_BACKOFF} and doubling on each consecutive failure, capped at {@code refresh}. Runs
     * on a single daemon thread. */
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ai-insights-pricing");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.schedule(this::runAndReschedule, 0, TimeUnit.MILLISECONDS);
    }

    /** Runs one refresh, then schedules the next one. Not synchronized itself so the lock is never held
     * across the network call and the reschedule together, avoiding a deadlock with {@link #close()}. */
    private void runAndReschedule() {
        boolean success = refresh();
        Duration delay = nextDelay(success);
        scheduleNext(delay);
    }

    private synchronized void scheduleNext(Duration delay) {
        if (scheduler != null) {
            scheduler.schedule(this::runAndReschedule, delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** Computes the delay before the next attempt and updates the backoff state. Success always resets the
     * backoff to {@link #INITIAL_BACKOFF} and waits the full {@code refresh} interval; a failure waits the
     * current backoff (capped at {@code refresh}) and doubles the backoff for the next failure. */
    synchronized Duration nextDelay(boolean success) {
        if (success) {
            nextBackoff = INITIAL_BACKOFF;
            return refresh;
        }
        Duration delay = nextBackoff.compareTo(refresh) <= 0 ? nextBackoff : refresh;
        Duration doubled = nextBackoff.multipliedBy(2);
        nextBackoff = doubled.compareTo(refresh) <= 0 ? doubled : refresh;
        return delay;
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
