package io.github.springaiinsights.autoconfigure;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;

/** Configuration of Spring AI Insights, bound from {@code ai-insights.*}. */
@ConfigurationProperties(InsightsProperties.PREFIX)
public class InsightsProperties {

    public static final String PREFIX = "ai-insights";

    /** Whether Spring AI Insights is enabled. */
    private boolean enabled = true;

    /** Application name recorded on every turn. Defaults to spring.application.name, else "default". */
    private @Nullable String application;

    /** Where turns are stored. */
    private StoreType store = StoreType.AUTO;

    /** How long turns are kept by a persistent store (at least 1 minute). */
    private Duration retention = Duration.ofDays(90);

    /** Whether prompt and answer text is recorded. Off by default because it may contain personal data. */
    private boolean captureContent = false;

    private final Identity identity = new Identity();

    private final Pricing pricing = new Pricing();

    private final Metrics metrics = new Metrics();

    private final Writer writer = new Writer();

    private final Memory memory = new Memory();

    private final Capture capture = new Capture();

    private final Mongodb mongodb = new Mongodb();

    private final Dashboard dashboard = new Dashboard();

    private final Feedback feedback = new Feedback();

    private final Knowledge knowledge = new Knowledge();

    private final Quality quality = new Quality();

    private final Report report = new Report();

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

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
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

    public Capture getCapture() {
        return capture;
    }

    public Mongodb getMongodb() {
        return mongodb;
    }

    public Dashboard getDashboard() {
        return dashboard;
    }

    public Feedback getFeedback() {
        return feedback;
    }

    public Knowledge getKnowledge() {
        return knowledge;
    }

    public Quality getQuality() {
        return quality;
    }

    public Report getReport() {
        return report;
    }

    public enum StoreType {
        /** MongoDB when Spring Data MongoDB is configured, otherwise in memory. */
        AUTO,
        /** Bounded in-memory store; data is lost on restart. */
        MEMORY,
        /** The application's MongoDB (5.0 or later); startup fails if Spring Data MongoDB is not configured. */
        MONGODB
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

    public static class Capture {

        /**
         * How long a finished turn waits for its model calls to report usage before it is recorded without them and
         * counted in ai.insights.turns.stalled.
         */
        private Duration stallTimeout = Duration.ofSeconds(30);

        /**
         * Whether to turn on Reactor's automatic context propagation (JVM-wide), so searches and tool calls that
         * Spring AI runs on other threads during a streamed turn are part of that turn.
         */
        private boolean reactorContextPropagation = true;

        public boolean isReactorContextPropagation() {
            return reactorContextPropagation;
        }

        public void setReactorContextPropagation(boolean reactorContextPropagation) {
            this.reactorContextPropagation = reactorContextPropagation;
        }

        public Duration getStallTimeout() {
            return stallTimeout;
        }

        public void setStallTimeout(Duration stallTimeout) {
            this.stallTimeout = stallTimeout;
        }
    }

    public static class Mongodb {

        /** Prefix of the collections used in the application's MongoDB database. */
        private String collectionPrefix = "ai_insights_";

        public String getCollectionPrefix() {
            return collectionPrefix;
        }

        public void setCollectionPrefix(String collectionPrefix) {
            this.collectionPrefix = collectionPrefix;
        }
    }

    public static class Dashboard {

        private static final Pattern PATH = Pattern.compile("^(/[A-Za-z0-9._~-]+)+$");

        private static final Pattern TRACE_URL = Pattern.compile("^https?://[^\\s\"'<>]+$");

        /** Whether the built-in dashboard and its JSON API are served. */
        private boolean enabled = true;

        /** Base path of the dashboard and of the feedback endpoint. */
        private String path = "/ai-insights";

        /** Role required to see the dashboard (checked with isUserInRole). */
        private String role = "AI_INSIGHTS";

        /** Serve the dashboard without Spring Security. Only for local development. */
        private boolean allowAnonymous = false;

        /**
         * Link from a turn to its trace in your tracing UI (Grafana Tempo, Jaeger, Zipkin...): an http(s) URL with a
         * {traceId} placeholder. No link when unset.
         */
        private @Nullable String traceUrl;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            if (path == null || !PATH.matcher(path).matches()) {
                throw new IllegalArgumentException("ai-insights.dashboard.path must look like /ai-insights");
            }
            this.path = path;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public boolean isAllowAnonymous() {
            return allowAnonymous;
        }

        public void setAllowAnonymous(boolean allowAnonymous) {
            this.allowAnonymous = allowAnonymous;
        }

        public @Nullable String getTraceUrl() {
            return traceUrl;
        }

        public void setTraceUrl(@Nullable String traceUrl) {
            if (traceUrl != null && !traceUrl.isBlank()
                    && (!TRACE_URL.matcher(traceUrl).matches() || !traceUrl.contains("{traceId}"))) {
                throw new IllegalArgumentException(
                        "ai-insights.dashboard.trace-url must be an http(s) URL containing {traceId}");
            }
            this.traceUrl = traceUrl == null || traceUrl.isBlank() ? null : traceUrl;
        }
    }

    public static class Quality {

        /** How long a conversation must be idle before it counts as abandoned (at least 1 minute). */
        private Duration abandonedAfter = Duration.ofMinutes(30);

        public Duration getAbandonedAfter() {
            return abandonedAfter;
        }

        public void setAbandonedAfter(Duration abandonedAfter) {
            if (abandonedAfter.compareTo(Duration.ofMinutes(1)) < 0) {
                throw new IllegalArgumentException(
                        "ai-insights.quality.abandoned-after must be at least 1 minute: " + abandonedAfter);
            }
            this.abandonedAfter = abandonedAfter;
        }
    }

    public static class Feedback {

        /** Whether the feedback endpoint ({dashboard.path}/feedback) is served. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Knowledge {

        /**
         * Metadata key whose value identifies a retrieved document, so chunks of one source count as one document.
         * Empty to use the vector store's document IDs.
         */
        private String documentKey = "source";

        /**
         * Whether VectorStore beans that emit no observations (e.g. the Bedrock Knowledge Base store) are wrapped so
         * their queries are recorded. Wrapped stores must be injected as VectorStore. Also observes the knowledge base
         * searches of BedrockAgentRuntimeClient beans, when spring-ai-insights-bedrock is on the classpath.
         */
        private boolean observeStores = true;

        private final Bedrock bedrock = new Bedrock();

        public Bedrock getBedrock() {
            return bedrock;
        }

        public String getDocumentKey() {
            return documentKey;
        }

        public void setDocumentKey(String documentKey) {
            this.documentKey = documentKey;
        }

        public boolean isObserveStores() {
            return observeStores;
        }

        public void setObserveStores(boolean observeStores) {
            this.observeStores = observeStores;
        }

        /** Lists the documents of Amazon Bedrock Knowledge Bases, to show those never retrieved. */
        public static class Bedrock {

            /** IDs of the knowledge bases to list; listing is off when empty. */
            private List<String> knowledgeBaseIds = new ArrayList<>();

            /** AWS region of the knowledge bases, when no BedrockAgentClient bean exists; else the SDK default. */
            private @Nullable String region;

            /** How often the document list is refreshed (at least 1 minute). */
            private Duration refresh = Duration.ofHours(1);

            public List<String> getKnowledgeBaseIds() {
                return knowledgeBaseIds;
            }

            public void setKnowledgeBaseIds(List<String> knowledgeBaseIds) {
                this.knowledgeBaseIds = knowledgeBaseIds;
            }

            public @Nullable String getRegion() {
                return region;
            }

            public void setRegion(@Nullable String region) {
                this.region = region;
            }

            public Duration getRefresh() {
                return refresh;
            }

            public void setRefresh(Duration refresh) {
                this.refresh = refresh;
            }
        }
    }

    public static class Report {

        /** Whether the weekly report is sent (when a channel is configured). */
        private boolean enabled = true;

        /** When the report is sent: a Spring cron expression, evaluated in the report zone. */
        private String cron = "0 0 8 * * MON";

        /** Time zone of the report's days and of the cron expression. Defaults to the JVM zone. */
        private @Nullable ZoneId zone;

        /** Slack incoming webhook URL. A secret: set it from an environment variable. */
        private @Nullable String slackWebhook;

        /** Email recipients; needs spring-boot-starter-mail. */
        private List<String> emailTo = new ArrayList<>();

        /** Email sender address. Defaults to the mail server's. */
        private @Nullable String emailFrom;

        /** Public URL of the dashboard, linked from the report. */
        private @Nullable URI dashboardUrl;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            if (cron == null || !CronExpression.isValidExpression(cron)) {
                throw new IllegalArgumentException(
                        "ai-insights.report.cron must be a Spring cron expression such as 0 0 8 * * MON: " + cron);
            }
            this.cron = cron;
        }

        public @Nullable ZoneId getZone() {
            return zone;
        }

        public void setZone(@Nullable ZoneId zone) {
            this.zone = zone;
        }

        /** Bound as text and validated when used: a bind failure would print the secret. */
        public @Nullable String getSlackWebhook() {
            return slackWebhook;
        }

        public void setSlackWebhook(@Nullable String slackWebhook) {
            this.slackWebhook = slackWebhook;
        }

        /**
         * The Slack webhook as an absolute http(s) URL; the failure message never contains the value.
         * @throws IllegalStateException when it is not one
         */
        URI slackWebhookUri() {
            String message = "ai-insights.report.slack-webhook must be an absolute http(s) URL";
            URI uri;
            try {
                uri = URI.create(slackWebhook == null ? "" : slackWebhook.strip());
            }
            catch (IllegalArgumentException ex) {
                throw new IllegalStateException(message);
            }
            try {
                return Objects.requireNonNull(httpUrl(uri, "slack-webhook"));
            }
            catch (IllegalArgumentException | NullPointerException ex) {
                throw new IllegalStateException(message);
            }
        }

        public List<String> getEmailTo() {
            return emailTo;
        }

        public void setEmailTo(List<String> emailTo) {
            this.emailTo = emailTo;
        }

        public @Nullable String getEmailFrom() {
            return emailFrom;
        }

        public void setEmailFrom(@Nullable String emailFrom) {
            this.emailFrom = emailFrom;
        }

        public @Nullable URI getDashboardUrl() {
            return dashboardUrl;
        }

        public void setDashboardUrl(@Nullable URI dashboardUrl) {
            this.dashboardUrl = httpUrl(dashboardUrl, "dashboard-url");
        }

        /** Rejects anything but an absolute http(s) URL; the message never contains the value (it may be secret). */
        private static @Nullable URI httpUrl(@Nullable URI uri, String name) {
            if (uri == null || uri.toString().isEmpty()) {
                return null;
            }
            String scheme = uri.getScheme();
            if (!uri.isAbsolute() || uri.getHost() == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("ai-insights.report." + name + " must be an absolute http(s) URL");
            }
            return uri;
        }
    }
}
