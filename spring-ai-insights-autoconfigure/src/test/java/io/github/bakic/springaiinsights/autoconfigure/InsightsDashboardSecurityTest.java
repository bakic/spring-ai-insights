package io.github.bakic.springaiinsights.autoconfigure;

import java.util.List;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.bakic.springaiinsights.ScriptedChatModel;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The application relies on Boot's default security chain; the dashboard reuses it and adds the role check. */
@SpringBootTest(classes = InsightsDashboardSecurityTest.App.class, properties = { "ai-insights.identity.secret=k",
        "ai-insights.store=memory", "spring.application.name=secured" })
@AutoConfigureMockMvc
class InsightsDashboardSecurityTest {

    @Autowired
    MockMvc mvc;

    @Test
    void bossSeesDashboard() throws Exception {
        mvc.perform(get("/ai-insights/api/config").with(user("boss").roles("AI_INSIGHTS")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.application").value("secured"));
    }

    @Test
    void userWithoutRoleIsForbidden() throws Exception {
        mvc.perform(get("/ai-insights/api/overview").with(user("joe")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("access denied"));
    }

    @Test
    void anonymousGetsAppLogin() throws Exception {
        MvcResult result = mvc.perform(get("/ai-insights/api/config")).andReturn();

        assertThat(result.getResponse().getStatus()).isIn(302, 401);
    }

    @Test
    void defaultChainStillProtectsApp() throws Exception {
        MvcResult result = mvc.perform(get("/hello")).andReturn();
        assertThat(result.getResponse().getStatus()).isIn(302, 401);

        mvc.perform(get("/hello").with(user("joe"))).andExpect(status().isOk());
    }

    @Nested
    @TestPropertySource(properties = "ai-insights.dashboard.role=OPS")
    class CustomRole {

        @Autowired
        MockMvc mvc;

        @Test
        void customRole() throws Exception {
            mvc.perform(get("/ai-insights/api/config").with(user("ops").roles("OPS"))).andExpect(status().isOk());
            mvc.perform(get("/ai-insights/api/config").with(user("boss").roles("AI_INSIGHTS")))
                .andExpect(status().isForbidden());
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        ChatModel chatModel(ObservationRegistry registry) {
            return new ScriptedChatModel(registry, prompt -> List.of(ScriptedChatModel.text("answer", 1, 1)));
        }

        @Bean
        HelloController helloController() {
            return new HelloController();
        }
    }

    @RestController
    static class HelloController {

        @GetMapping("/hello")
        String hello() {
            return "hello";
        }
    }
}
