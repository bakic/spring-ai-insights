package io.github.springaiinsights.demo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import io.github.springaiinsights.analytics.InsightsAnalytics;
import io.github.springaiinsights.analytics.InsightsFilter;
import io.github.springaiinsights.analytics.QualitySummary;
import io.github.springaiinsights.analytics.RetrievalUsage;
import io.github.springaiinsights.analytics.ToolUsage;
import io.github.springaiinsights.analytics.UsageSummary;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "demo.traffic.interval=100ms")
class HelpdeskDemoTest {

    @LocalServerPort
    int port;

    @Autowired
    InsightsAnalytics analytics;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void dashboardShowsTrafficAfterStartup() {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            InsightsFilter filter = new InsightsFilter("helpdesk-demo", Instant.now().minusSeconds(3600),
                    Instant.now().plusSeconds(60), null, ZoneOffset.UTC);
            UsageSummary summary = analytics.summary(filter);
            assertThat(summary.turns()).isGreaterThanOrEqualTo(10);
            assertThat(summary.cost()).isPositive();
            assertThat(analytics.tools(filter)).extracting(ToolUsage::tool)
                .contains("lookupOrder", "serviceStatus", "openTicket");
            assertThat(analytics.tools(filter)).filteredOn(t -> t.tool().equals("lookupOrder"))
                .singleElement()
                .satisfies(t -> assertThat(t.errors()).isPositive());
            long queries = analytics.retrievals(filter).stream().mapToLong(RetrievalUsage::queries).sum();
            long empty = analytics.retrievals(filter).stream().mapToLong(RetrievalUsage::emptyQueries).sum();
            assertThat(empty).isPositive();
            assertThat(queries - empty).isPositive();
            QualitySummary quality = analytics.quality(filter);
            assertThat(quality.positiveFeedback()).isPositive();
            assertThat(quality.negativeFeedback()).isPositive();
        });
    }

    @Test
    void chatEndpointAnswersSignedInUser() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/api/chat"))
            .header("Authorization", basic("alice", "alice"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"conversationId\":\"c-1\",\"question\":\"How long do refunds take?\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"turnId\":\"").contains("help center");
    }

    @Test
    void dashboardRequiresTheRole() throws Exception {
        assertThat(get("/ai-insights/", basic("alice", "alice")).statusCode()).isEqualTo(403);
        assertThat(get("/ai-insights/", basic("admin", "admin")).statusCode()).isEqualTo(200);
    }

    @Test
    void prometheusShowsToolCalls() {
        await().atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(get("/actuator/prometheus", null).body()).contains("ai_insights_tool_calls_total"));
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
