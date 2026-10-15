package io.github.springaiinsights.report;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Posts the weekly report to a Slack incoming webhook. The webhook URL is a secret: it never appears in an
 * exception message.
 */
public final class SlackReportSender implements ReportSender {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final URI webhook;

    private final HttpClient client;

    public SlackReportSender(URI webhook, HttpClient client) {
        this.webhook = Objects.requireNonNull(webhook, "webhook");
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String name() {
        return "slack";
    }

    @Override
    public void send(WeeklyReport report) throws InterruptedException {
        String body = "{\"text\":" + json(ReportFormat.slack(report)) + ",\"unfurl_links\":false}";
        HttpRequest request = HttpRequest.newBuilder(this.webhook)
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        int status;
        try {
            status = this.client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
        catch (IOException ex) {
            throw new IllegalStateException(hide(ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        }
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Slack webhook answered HTTP " + status);
        }
    }

    private String hide(String message) {
        String hidden = message.replace(this.webhook.toString(), "<slack-webhook>");
        String path = this.webhook.getRawPath();
        return (path == null || path.isEmpty() || "/".equals(path)) ? hidden : hidden.replace(path, "<slack-webhook>");
    }

    static String json(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        out.append(String.format("\\u%04x", (int) c));
                    }
                    else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

}
