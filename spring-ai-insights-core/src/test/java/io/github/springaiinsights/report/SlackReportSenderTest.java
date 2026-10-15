package io.github.springaiinsights.report;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlackReportSenderTest {

    private static final String SECRET_PATH = "/services/T000/B000/s3cr3t";

    private final AtomicReference<String> body = new AtomicReference<>();

    private final AtomicReference<String> contentType = new AtomicReference<>();

    private HttpServer server;

    private URI start(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(SECRET_PATH, exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + SECRET_PATH);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsTextAsJson() throws Exception {
        SlackReportSender sender = new SlackReportSender(start(200), HttpClient.newHttpClient());
        assertThat(sender.name()).isEqualTo("slack");
        sender.send(ReportFixtures.REPORT);
        assertThat(contentType.get()).isEqualTo("application/json; charset=utf-8");
        assertThat((String) JsonPath.read(body.get(), "$.text")).isEqualTo(ReportFormat.slack(ReportFixtures.REPORT));
        assertThat((Boolean) JsonPath.read(body.get(), "$.unfurl_links")).isFalse();
    }

    @Test
    void non2xxFails() throws Exception {
        SlackReportSender sender = new SlackReportSender(start(403), HttpClient.newHttpClient());
        assertThatThrownBy(() -> sender.send(ReportFixtures.REPORT)).isInstanceOf(IllegalStateException.class)
            .hasMessage("Slack webhook answered HTTP 403");
    }

    @Test
    void failureMessageHidesWebhook() throws Exception {
        URI webhook = start(200);
        server.stop(0);
        server = null;
        SlackReportSender sender = new SlackReportSender(webhook, HttpClient.newHttpClient());
        assertThatThrownBy(() -> sender.send(ReportFixtures.REPORT)).isInstanceOf(IllegalStateException.class)
            .satisfies(e -> {
                assertThat(e.getMessage()).doesNotContain("s3cr3t");
                assertThat(e.getCause()).isNull();
                assertThat(e.toString()).doesNotContain("s3cr3t");
            });
    }

    @Test
    void jsonEscapesControlCharacters() {
        assertThat(SlackReportSender.json("a\"b\\c\nd\u0001")).isEqualTo("\"a\\\"b\\\\c\\nd\\u0001\"");
    }
}
