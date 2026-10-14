package io.github.springaiinsights.autoconfigure;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration of Spring AI Insights, bound from {@code ai-insights.*}. */
@ConfigurationProperties(InsightsProperties.PREFIX)
public class InsightsProperties {

    public static final String PREFIX = "ai-insights";

    /** Whether Spring AI Insights is enabled. */
    private boolean enabled = true;

    /** Application name recorded on every turn. Defaults to spring.application.name, else "default". */
    private @Nullable String application;

    /** Where turns are stored. In this version both values use the in-memory store. */
    private StoreType store = StoreType.AUTO;

    /** Whether prompt and answer text is recorded. Off by default because it may contain personal data. */
    private boolean captureContent = false;

    private final Identity identity = new Identity();

    private final Pricing pricing = new Pricing();

    private final Metrics metrics = new Metrics();

    private final Writer writer = new Writer();

    private final Memory memory = new Memory();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public @Nullable String getApplication() {
        return application;
    }

    public void setApplication(@Nullable String application) {
        this.application = application;
    }

    public StoreType getStore() {
        return store;
    }

    public void setStore(StoreType store) {
        this.store = store;
    }

    public boolean isCaptureContent() {
        return captureContent;
    }

    public void setCaptureContent(boolean captureContent) {
        this.captureContent = captureContent;
    }

    public Identity getIdentity() {
        return identity;
    }

    public Pricing getPricing() {
        return pricing;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public Writer getWriter() {
        return writer;
    }

    public Memory getMemory() {
        return memory;
    }

    public enum StoreType {
        /** The best store available on the classpath; the in-memory store in this version. */
        AUTO,
        /** Bounded in-memory store; data is lost on restart. */
        MEMORY
    }

    public static class Identity {

        /** Whether user IDs are replaced with HMAC-SHA256(secret, userId) before they are recorded. */
        private boolean pseudonymise = true;

        /** HMAC secret. When unset, a random key is used and pseudonyms change on restart. */
        private @Nullable String secret;

        /** JWT claim holding the tenant ID. */
        private String tenantClaim = "tenant_id";

        public boolean isPseudonymise() {
            return pseudonymise;
        }

        public void setPseudonymise(boolean pseudonymise) {
            this.pseudonymise = pseudonymise;
        }

        public @Nullable String getSecret() {
            return secret;
        }

        public void setSecret(@Nullable String secret) {
            this.secret = secret;
        }

        public String getTenantClaim() {
            return tenantClaim;
        }

        public void setTenantClaim(String tenantClaim) {
            this.tenantClaim = tenantClaim;
        }
    }

    public static class Pricing {

        /** Currency of the configured prices. */
        private String currency = "USD";

        /** Prices per 1,000,000 tokens. */
        private List<ModelPricing> models = new ArrayList<>();

        public String getCurrency() {
            return currency;
        }

        public void setCurrency(String currency) {
            this.currency = currency;
        }

        public List<ModelPricing> getModels() {
            return models;
        }

        public void setModels(List<ModelPricing> models) {
            this.models = models;
        }
    }

    public static class ModelPricing {

        /** Model ID, matched exactly or as a substring of the model ID reported by the provider. */
        private @Nullable String model;

        /** Price of 1,000,000 input tokens. */
        private @Nullable BigDecimal input;

        /** Price of 1,000,000 output tokens. */
        private @Nullable BigDecimal output;

        /** Price of 1,000,000 tokens read from the prompt cache. */
        private BigDecimal cacheRead = BigDecimal.ZERO;

        /** Price of 1,000,000 tokens written to the prompt cache. */
        private BigDecimal cacheWrite = BigDecimal.ZERO;

        /** Whether the provider's input token count already includes cached tokens. */
        private boolean inputIncludesCached = false;

        public @Nullable String getModel() {
            return model;
        }

        public void setModel(@Nullable String model) {
            this.model = model;
        }

        public @Nullable BigDecimal getInput() {
            return input;
        }

        public void setInput(@Nullable BigDecimal input) {
            this.input = input;
        }

        public @Nullable BigDecimal getOutput() {
            return output;
        }

        public void setOutput(@Nullable BigDecimal output) {
            this.output = output;
        }

        public BigDecimal getCacheRead() {
            return cacheRead;
        }

        public void setCacheRead(BigDecimal cacheRead) {
            this.cacheRead = cacheRead;
        }

        public BigDecimal getCacheWrite() {
            return cacheWrite;
        }

        public void setCacheWrite(BigDecimal cacheWrite) {
            this.cacheWrite = cacheWrite;
        }

        public boolean isInputIncludesCached() {
            return inputIncludesCached;
        }

        public void setInputIncludesCached(boolean inputIncludesCached) {
            this.inputIncludesCached = inputIncludesCached;
        }
    }

    public static class Metrics {

        /** Whether metrics carry a tenant tag. Only enable with a small, bounded number of tenants. */
        private boolean tenantTag = false;

        public boolean isTenantTag() {
            return tenantTag;
        }

        public void setTenantTag(boolean tenantTag) {
            this.tenantTag = tenantTag;
        }
    }

    public static class Writer {

        /** Turns waiting to be stored; further turns are dropped and counted. */
        private int queueCapacity = 10_000;

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    public static class Memory {

        /** Turns kept by the in-memory store; the oldest are evicted first. */
        private int maxTurns = 10_000;

        public int getMaxTurns() {
            return maxTurns;
        }

        public void setMaxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
        }
    }
}
