package io.github.springaiinsights.observability;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Loads the rules and the dashboard into real Prometheus and Grafana that scrape the real registry. */
class LiveStackTest {

    static final String GRAFANA_IMAGE = "grafana/grafana:12.1.0";

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final PrometheusMeterRegistry REGISTRY = SampleScrape.registry();

    private static HttpServer server;

    private static ScheduledExecutorService traffic;

    private static Network network;

    private static GenericContainer<?> prometheus;

    private static GenericContainer<?> grafana;

    @TempDir
    static Path config;

    @BeforeAll
    static void startStack() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/metrics", exchange -> {
            byte[] body = REGISTRY.scrape().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        Testcontainers.exposeHostPorts(port);
        traffic = Executors.newSingleThreadScheduledExecutor();
        traffic.scheduleAtFixedRate(() -> SampleScrape.feed(REGISTRY), 0, 300, TimeUnit.MILLISECONDS);

        Path prometheusYml = Files.writeString(config.resolve("prometheus.yml"), """
                global:
                  scrape_interval: 1s
                  evaluation_interval: 1s
                rule_files:
                  - /etc/prometheus/rules/ai-insights-alerts.yml
                scrape_configs:
                  - job_name: app
                    static_configs:
                      - targets: ['host.testcontainers.internal:%d']
                """.formatted(port));
        Path datasource = Files.writeString(config.resolve("datasource.yml"), """
                apiVersion: 1
                datasources:
                  - name: Prometheus
                    type: prometheus
                    uid: prometheus
                    access: proxy
                    url: http://prometheus:9090
                    isDefault: true
                """);
        Path provider = Files.writeString(config.resolve("dashboards.yml"), """
                apiVersion: 1
                providers:
                  - name: ai-insights
                    type: file
                    options:
                      path: /var/lib/grafana/dashboards
                """);

        network = Network.newNetwork();
        prometheus = new GenericContainer<>(AlertRulesTest.PROMETHEUS_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("prometheus")
            .withExposedPorts(9090)
            .withCopyFileToContainer(MountableFile.forHostPath(prometheusYml), "/etc/prometheus/prometheus.yml")
            .withCopyFileToContainer(MountableFile.forHostPath(AlertRulesTest.RULES),
                    "/etc/prometheus/rules/ai-insights-alerts.yml")
            .waitingFor(Wait.forHttp("/-/ready").forPort(9090));
        grafana = new GenericContainer<>(GRAFANA_IMAGE)
            .withNetwork(network)
            .withExposedPorts(3000)
            .withEnv("GF_SECURITY_ADMIN_PASSWORD", "admin")
            .withCopyFileToContainer(MountableFile.forHostPath(datasource),
                    "/etc/grafana/provisioning/datasources/datasource.yml")
            .withCopyFileToContainer(MountableFile.forHostPath(provider),
                    "/etc/grafana/provisioning/dashboards/dashboards.yml")
            .withCopyFileToContainer(MountableFile.forHostPath(DashboardTest.DASHBOARD),
                    "/var/lib/grafana/dashboards/ai-insights-dashboard.json")
            .waitingFor(Wait.forHttp("/api/health").forPort(3000))
            .withStartupTimeout(Duration.ofMinutes(3));
        prometheus.start();
        grafana.start();

        // Wait for enough scrapes that every rate window holds several samples.
        await().atMost(Duration.ofMinutes(1)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            List<String> samples = query("count_over_time(ai_insights_turns_total{outcome=\"success\",streaming=\"false\"}[1m])")
                .read("$.data.result[*].value[1]");
            assertThat(samples).as("scrape target: %s", get(prometheusUrl("/api/v1/targets"), null).jsonString())
                .singleElement()
                .satisfies(v -> assertThat(Double.parseDouble(v)).isGreaterThanOrEqualTo(8));
        });
    }

    @AfterAll
    static void stopStack() {
        if (traffic != null) {
            traffic.shutdownNow();
        }
        if (grafana != null) {
            grafana.stop();
        }
        if (prometheus != null) {
            prometheus.stop();
        }
        if (network != null) {
            network.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void prometheusLoadsTheRules() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            DocumentContext rules = get(prometheusUrl("/api/v1/rules"), null);
            List<String> names = rules.read("$.data.groups[*].rules[*].name");
            List<String> health = rules.read("$.data.groups[*].rules[*].health");
            assertThat(names).containsExactlyInAnyOrder("AiInsightsCostSpike", "AiInsightsToolErrorRate",
                    "AiInsightsEmptyRetrievalRate", "AiInsightsSlowFirstToken", "AiInsightsEventsLost");
            assertThat(health).containsOnly("ok");
        });
    }

    @Test
    void everyPanelQueryReturnsDataAgainstARealScrape() throws Exception {
        List<String> expressions = DashboardTest.expressions();
        assertThat(expressions).isNotEmpty();
        for (String expr : expressions) {
            String concrete = concrete(expr);
            DocumentContext result = query(concrete);
            assertThat(result.read("$.status", String.class)).as(concrete).isEqualTo("success");
            List<Object> series = result.read("$.data.result");
            assertThat(series).as("no data for %s", concrete).isNotEmpty();
        }
    }

    @Test
    void grafanaLoadsTheDashboard() throws Exception {
        DocumentContext loaded = get(grafanaUrl("/api/dashboards/uid/ai-insights"), admin());
        assertThat(loaded.read("$.dashboard.title", String.class)).isEqualTo("Spring AI Insights");
        List<Object> panels = loaded.read("$.dashboard.panels");
        List<Object> filePanels = DashboardTest.dashboard().read("$.panels");
        assertThat(panels).hasSameSizeAs(filePanels);
    }

    @Test
    void grafanaQueriesPrometheusThroughTheProvisionedDatasource() throws Exception {
        String body = """
                {"from":"now-5m","to":"now","queries":[{"refId":"A","datasource":{"type":"prometheus","uid":"prometheus"},
                "expr":"sum(ai_insights_turns_total)","instant":true}]}""";
        HttpRequest request = HttpRequest.newBuilder(URI.create(grafanaUrl("/api/ds/query")))
            .header("Authorization", admin())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<Object> values = JsonPath.parse(response.body()).read("$.results.A.frames[0].data.values[1]");
        assertThat(values).isNotEmpty();
    }

    /** Replaces the Grafana variables the way Grafana does for one application over a short range. */
    static String concrete(String expr) {
        return expr.replace("$application", ".*").replace("$__rate_interval", "1m").replace("$__range", "5m");
    }

    private static DocumentContext query(String promql) throws IOException, InterruptedException {
        return get(prometheusUrl("/api/v1/query?query=" + URLEncoder.encode(promql, StandardCharsets.UTF_8)), null);
    }

    private static DocumentContext get(String url, String authorization) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s -> %s", url, response.body()).isEqualTo(200);
        return JsonPath.parse(response.body());
    }

    private static String prometheusUrl(String path) {
        return "http://" + prometheus.getHost() + ":" + prometheus.getMappedPort(9090) + path;
    }

    private static String grafanaUrl(String path) {
        return "http://" + grafana.getHost() + ":" + grafana.getMappedPort(3000) + path;
    }

    private static String admin() {
        return "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));
    }
}
