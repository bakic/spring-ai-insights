package io.github.springaiinsights.web;

import java.time.Clock;
import java.time.ZoneOffset;

import io.github.springaiinsights.analytics.ContractTurns;
import io.github.springaiinsights.report.WeeklyReports;
import io.github.springaiinsights.store.InMemoryEventStore;
import org.junit.jupiter.api.Test;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static io.github.springaiinsights.analytics.ContractTurns.TO;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DashboardRoutesTest {

    static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    private static final String RANGE = "?from=2026-09-30T22:00:00Z&to=2026-10-02T22:00:00Z&zone=Europe/Paris";

    private final InMemoryEventStore store = new InMemoryEventStore(1000);

    {
        ContractTurns.all().forEach(store::save);
        ContractTurns.help().forEach(store::save);
        ContractTurns.feedback().forEach(store::saveFeedback);
    }

    private MockMvc mvc(boolean allowAnonymous) {
        Clock clock = Clock.fixed(TO, ZoneOffset.UTC);
        DashboardPages pages = new DashboardPages(store, store, "shop", "USD", clock);
        return MockMvcBuilders.routerFunctions(DashboardRoutes.create("/ai-insights", "AI_INSIGHTS", allowAnonymous,
                pages, clock)).build();
    }

    private MockMvc reportMvc(boolean allowAnonymous) {
        Clock clock = Clock.fixed(TO.plus(java.time.Duration.ofDays(2)), ZoneOffset.UTC);
        DashboardPages pages = new DashboardPages(store, store, "shop", "USD", clock);
        WeeklyReports reports = new WeeklyReports(store, "USD", ZoneOffset.UTC, clock, null);
        return MockMvcBuilders.routerFunctions(DashboardRoutes.create("/ai-insights", "AI_INSIGHTS", allowAnonymous,
                pages, clock, reports)).build();
    }

    @Test
    void reportJson() throws Exception {
        reportMvc(true).perform(get("/ai-insights/api/report"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.application").value("shop"))
            .andExpect(jsonPath("$.firstDay").value("2026-09-27"))
            .andExpect(jsonPath("$.lastDay").value("2026-10-03"))
            .andExpect(jsonPath("$.current.conversations").isNumber())
            .andExpect(jsonPath("$.projection.month").value("2026-10"));
        reportMvc(true).perform(get("/ai-insights/api/report?app=help"))
            .andExpect(jsonPath("$.application").value("help"));
        reportMvc(true).perform(get("/ai-insights/api/report?app=" + "a".repeat(201)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void reportHtml() throws Exception {
        reportMvc(true).perform(get("/ai-insights/report"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(header().string("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; "
                    + "frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("AI assistant weekly report: shop")));
    }

    @Test
    void reportNeedsRole() throws Exception {
        MockMvc mvc = reportMvc(false);
        mvc.perform(get("/ai-insights/api/report")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ai-insights/report")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ai-insights/report").principal(() -> "joe")).andExpect(status().isForbidden());
    }

    @Test
    void reportRoutesAbsentWithoutReports() throws Exception {
        MockMvc mvc = mvc(true);
        mvc.perform(get("/ai-insights/api/report")).andExpect(status().isNotFound());
        mvc.perform(get("/ai-insights/report")).andExpect(status().isNotFound());
    }

    @Test
    void allowAnonymousServesJson() throws Exception {
        mvc(true).perform(get("/ai-insights/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.application").value("shop"))
            .andExpect(jsonPath("$.perInstance").value(true));
    }

    @Test
    void jsonShapes() throws Exception {
        MockMvc mvc = mvc(true);
        mvc.perform(get("/ai-insights/api/overview" + RANGE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.current.turns").value(6))
            .andExpect(jsonPath("$.days[0].start").value("2026-09-30T22:00:00Z"))
            .andExpect(jsonPath("$.recentTurns[0].turnId").value("T5"));
        mvc.perform(get("/ai-insights/api/usage" + RANGE)).andExpect(jsonPath("$.turnsByHourOfDay.length()").value(24));
        mvc.perform(get("/ai-insights/api/cost" + RANGE)).andExpect(jsonPath("$.monthEndProjection").value(0.020488));
        mvc.perform(get("/ai-insights/api/knowledge" + RANGE)).andExpect(jsonPath("$.retrievalTurns").value(2));
        mvc.perform(get("/ai-insights/api/tools" + RANGE)).andExpect(jsonPath("$.tools[0].tool").value("lookup"));
        mvc.perform(get("/ai-insights/api/tenants" + RANGE + "&tenant=acme"))
            .andExpect(jsonPath("$.tenants[0].tenantId").value("acme"))
            .andExpect(jsonPath("$.tenants[1].tenantId").value("globex"))
            .andExpect(jsonPath("$.truncated").value(false));
        mvc.perform(get("/ai-insights/api/tools" + RANGE)).andExpect(jsonPath("$.neverCalled[0]").value("search"))
            .andExpect(jsonPath("$.offeredRecorded").value(true));
        mvc.perform(get("/ai-insights/api/knowledge" + RANGE))
            .andExpect(jsonPath("$.leastDocuments").isArray())
            .andExpect(jsonPath("$.coverage").isArray());
        mvc.perform(get("/ai-insights/api/quality" + RANGE)).andExpect(jsonPath("$.abandoned.count").value(2));
        mvc.perform(get("/ai-insights/api/quality" + RANGE + "&app=help"))
            .andExpect(jsonPath("$.recentNegative[0].comment").value("too long"));
        mvc.perform(get("/ai-insights/api/conversation?app=help&id=h1"))
            .andExpect(jsonPath("$.turns.length()").value(2))
            .andExpect(jsonPath("$.feedback.length()").value(2));
    }

    @Test
    void badQueryGives400() throws Exception {
        mvc(true).perform(get("/ai-insights/api/overview?zone=Mars/Base"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid zone: Mars/Base"));
    }

    @Test
    void missingConversationIdGives400() throws Exception {
        mvc(true).perform(get("/ai-insights/api/conversation?app=help"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("id is required"));
        mvc(true).perform(get("/ai-insights/api/conversation?id=" + "x".repeat(201)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("id is too long"));
    }

    @Test
    void apiResponsesAreNotCached() throws Exception {
        mvc(true).perform(get("/ai-insights/api/config"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Content-Security-Policy", CSP))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"));
        mvc(true).perform(get("/ai-insights/api/overview?zone=Mars/Base"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Content-Security-Policy", CSP));
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc(false).perform(get("/ai-insights/api/config"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("authentication required"))
            .andExpect(header().string("Content-Security-Policy", CSP));
    }

    @Test
    void principalWithoutRoleIsForbidden() throws Exception {
        mvc(false).perform(get("/ai-insights/api/config").principal(() -> "joe"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("access denied"));
    }

    @Test
    void principalWithRoleIsServed() throws Exception {
        mvc(false).perform(get("/ai-insights/api/config").principal(() -> "boss").with(request -> {
            request.addUserRole("AI_INSIGHTS");
            return request;
        })).andExpect(status().isOk());
    }
}
