package io.github.springaiinsights.web;

import java.time.Clock;
import java.time.Duration;

import io.github.springaiinsights.store.InMemoryEventStore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/** Serves the dashboard with generated data on http://localhost:8089/ai-insights/. Run {@code ./gradlew previewDashboard}. */
@SpringBootConfiguration
@EnableAutoConfiguration
public class DashboardPreview {

    public static void main(String[] args) {
        System.setProperty("server.port", System.getProperty("server.port", "8089"));
        SpringApplication.run(DashboardPreview.class, args);
    }

    @Bean
    InMemoryEventStore previewStore() {
        InMemoryEventStore store = new InMemoryEventStore(5000);
        PreviewData.seed(store, Clock.systemUTC().instant());
        return store;
    }

    @Bean
    RouterFunction<ServerResponse> previewDashboard(InMemoryEventStore store) {
        Clock clock = Clock.systemUTC();
        return DashboardRoutes.create("/ai-insights", "AI_INSIGHTS", true,
                new DashboardPages(store, store, "support-assistant", "EUR", clock,
                        new DashboardOptions(PreviewData.catalogs(), Duration.ofMinutes(30),
                                "https://grafana.example.com/explore?traceId={traceId}")),
                clock);
    }

}
