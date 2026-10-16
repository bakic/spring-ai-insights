package io.github.springaiinsights.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

import io.github.springaiinsights.store.InMemoryEventStore;
import org.junit.jupiter.api.Test;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DashboardUiTest {

    private static final String SVG_NAMESPACE = "http://www.w3.org/2000/svg";

    private static final List<String> FILES = List.of("index.html", "markdown.js", "app.js", "app.css");

    private final InMemoryEventStore store = new InMemoryEventStore(10);

    private MockMvc mvc(boolean allowAnonymous) {
        DashboardPages pages = new DashboardPages(store, store, "shop", "USD", Clock.systemUTC());
        return MockMvcBuilders
            .routerFunctions(DashboardRoutes.create("/ops/ai", "AI_INSIGHTS", allowAnonymous, pages,
                    Clock.systemUTC()))
            .build();
    }

    private static String file(String name) throws IOException {
        try (InputStream in = DashboardUiTest.class.getResourceAsStream("/ai-insights-dashboard/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void rootRedirectsToSlash() throws Exception {
        mvc(true).perform(get("/ops/ai")).andExpect(status().isFound()).andExpect(redirectedUrl("ai/"));
    }

    @Test
    void indexServedWithCsp() throws Exception {
        mvc(true).perform(get("/ops/ai/"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("assets/app.js")))
            .andExpect(header().string("Content-Security-Policy", DashboardRoutesTest.CSP));
    }

    @Test
    void assetsServed() throws Exception {
        MockMvc mvc = mvc(true);
        mvc.perform(get("/ops/ai/assets/app.js"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/javascript"));
        mvc.perform(get("/ops/ai/assets/app.css"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/css"));
        mvc.perform(get("/ops/ai/assets/fonts/public-sans-latin.woff2"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("font/woff2"));
        mvc.perform(get("/ops/ai/assets/fonts/OFL.txt")).andExpect(status().isOk());
        mvc.perform(get("/ops/ai/assets/missing.js")).andExpect(status().isNotFound());
    }

    @Test
    void cspHeaderOnEveryDashboardResponse() throws Exception {
        MockMvc mvc = mvc(true);
        for (String url : List.of("/ops/ai", "/ops/ai/", "/ops/ai/assets/app.js", "/ops/ai/assets/app.css",
                "/ops/ai/api/config", "/ops/ai/api/overview?zone=bad")) {
            mvc.perform(get(url))
                .andExpect(header().string("Content-Security-Policy", DashboardRoutesTest.CSP))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        }
    }

    @Test
    void assetsNeedAccess() throws Exception {
        MockMvc mvc = mvc(false);
        for (String url : List.of("/ops/ai", "/ops/ai/", "/ops/ai/assets/app.js")) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).principal(() -> "joe")).andExpect(status().isForbidden());
        }
    }

    @Test
    void scriptsNeverUseInnerHtml() throws IOException {
        for (String script : List.of("markdown.js", "app.js")) {
            String js = file(script);
            for (String sink : List.of("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(",
                    "new Function")) {
                assertThat(js).as(script + ": " + sink).doesNotContain(sink);
            }
        }
    }

    @Test
    void noExternalUrls() throws IOException {
        for (String name : FILES) {
            // The SVG namespace is an identifier for createElementNS, never fetched.
            assertThat(file(name).replace(SVG_NAMESPACE, "")).as(name).doesNotContain("http://").doesNotContain("https://");
        }
    }

    @Test
    void onlyRelativeUrls() throws IOException {
        String index = file("index.html");
        assertThat(index).contains("src=\"assets/app.js\"").contains("href=\"assets/app.css\"");
        // app.js reads the Markdown reader at render time, so the reader must load first.
        assertThat(index.indexOf("src=\"assets/markdown.js\"")).isPositive().isLessThan(index.indexOf("src=\"assets/app.js\""));
        assertThat(file("app.js")).doesNotContain("'/api").doesNotContain("\"/api").doesNotContain("`/api");
    }
}
