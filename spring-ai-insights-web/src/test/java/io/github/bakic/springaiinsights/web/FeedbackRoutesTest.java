package io.github.bakic.springaiinsights.web;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import io.github.bakic.springaiinsights.analytics.ContractTurns;
import io.github.bakic.springaiinsights.feedback.Feedback;
import io.github.bakic.springaiinsights.feedback.FeedbackStore;
import io.github.bakic.springaiinsights.feedback.InsightsFeedback;
import io.github.bakic.springaiinsights.feedback.Rating;
import io.github.bakic.springaiinsights.metrics.InsightsMetrics;
import io.github.bakic.springaiinsights.store.AsyncEventWriter;
import io.github.bakic.springaiinsights.store.InMemoryEventStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FeedbackRoutesTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final InsightsMetrics metrics = new InsightsMetrics(registry, "help", false);

    private final InMemoryEventStore store = new InMemoryEventStore(100);

    private final AsyncEventWriter writer = new AsyncEventWriter(store, 10, metrics);

    private MockMvc mvc(FeedbackStore feedbackStore) {
        var feedback = new InsightsFeedback(store, feedbackStore, writer, null, metrics,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return MockMvcBuilders.routerFunctions(FeedbackRoutes.create("/ai-insights", feedback, metrics)).build();
    }

    private MockMvc mvc() {
        return mvc(store);
    }

    @AfterEach
    void close() {
        writer.close();
    }

    private static MockHttpServletRequestBuilder rate(String user, String json) {
        var request = post("/ai-insights/feedback").contentType(MediaType.APPLICATION_JSON).content(json);
        return user == null ? request : request.principal(() -> user);
    }

    @Test
    void ratesOwnTurn() throws Exception {
        store.save(ContractTurns.H1);

        mvc().perform(rate("v1", """
                {"turnId": "H1", "rating": "Down", "comment": "wrong answer"}"""))
            .andExpect(status().isNoContent());

        assertThat(store.conversationFeedback("help", "h1")).containsExactly(new Feedback("H1", "help", "h1", "v1",
                "acme", Rating.DOWN, "wrong answer", NOW));
    }

    @Test
    void othersTurnGives404() throws Exception {
        store.save(ContractTurns.H1);

        String others = mvc().perform(rate("v2", """
                {"turnId": "H1", "rating": "up"}"""))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();
        String unknown = mvc().perform(rate("v2", """
                {"turnId": "nope", "rating": "up"}"""))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();

        assertThat(others).isEqualTo(unknown).isEqualTo("{\"error\":\"turn not found\"}");
        assertThat(store.conversationFeedback("help", "h1")).isEmpty();
    }

    @Test
    void noPrincipalGives401() throws Exception {
        store.save(ContractTurns.H4);

        mvc().perform(rate(null, """
                {"turnId": "H4", "rating": "up"}"""))
            .andExpect(status().isUnauthorized())
            .andExpect(content().json("{\"error\":\"authentication required\"}"));
    }

    @Test
    void badRatingGives400() throws Exception {
        mvc().perform(rate("v1", """
                {"turnId": "H1", "rating": "meh"}"""))
            .andExpect(status().isBadRequest())
            .andExpect(content().json("{\"error\":\"rating must be up or down\"}"));
        mvc().perform(rate("v1", """
                {"turnId": "H1"}"""))
            .andExpect(status().isBadRequest());
    }

    @Test
    void missingTurnIdGives400() throws Exception {
        mvc().perform(rate("v1", """
                {"rating": "up"}"""))
            .andExpect(status().isBadRequest())
            .andExpect(content().json("{\"error\":\"turnId is required\"}"));
    }

    @Test
    void malformedJsonGives400() throws Exception {
        mvc().perform(rate("v1", "{\"turnId\": "))
            .andExpect(status().isBadRequest())
            .andExpect(content().json("{\"error\":\"malformed request body\"}"));
    }

    @Test
    void tooLongCommentGives400() throws Exception {
        store.save(ContractTurns.H1);

        mvc().perform(rate("v1", "{\"turnId\": \"H1\", \"rating\": \"up\", \"comment\": \"" + "x".repeat(1001) + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().json("{\"error\":\"comment must not exceed 1000 characters\"}"));
    }

    @Test
    void storeFailureGives503() throws Exception {
        store.save(ContractTurns.H1);
        FeedbackStore broken = f -> {
            throw new IllegalStateException("db down");
        };

        mvc(broken).perform(rate("v1", """
                {"turnId": "H1", "rating": "up"}"""))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().json("{\"error\":\"feedback could not be saved\"}"));
        assertThat(registry.get("ai.insights.store.errors").counter().count()).isEqualTo(1);
    }
}
