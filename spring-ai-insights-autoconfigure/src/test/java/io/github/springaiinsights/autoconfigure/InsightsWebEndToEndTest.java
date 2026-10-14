package io.github.springaiinsights.autoconfigure;

import java.util.List;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import io.github.springaiinsights.ScriptedChatModel;
import io.github.springaiinsights.identity.InsightsContextKeys;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = { "ai-insights.identity.secret=k", "ai-insights.store=memory",
        "spring.application.name=web-e2e" })
@AutoConfigureMockMvc
class InsightsWebEndToEndTest {

    @Autowired
    MockMvc mvc;

    private String chatAs(String user) throws Exception {
        return mvc.perform(get("/chat").with(user(user))).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString();
    }

    private static String rating(String turnId) {
        return "{\"turnId\": \"" + turnId + "\", \"rating\": \"up\"}";
    }

    @Test
    void feedbackRightAfterChat() throws Exception {
        String turnId = chatAs("alice");

        mvc.perform(post("/ai-insights/feedback").with(user("alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(rating(turnId))).andExpect(status().isNoContent());
    }

    @Test
    void bobCannotRateAlicesTurn() throws Exception {
        String turnId = chatAs("alice");

        mvc.perform(post("/ai-insights/feedback").with(user("bob")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(rating(turnId))).andExpect(status().isNotFound());
    }

    @Test
    void appChainStillAppliesCsrf() throws Exception {
        String turnId = chatAs("alice");

        mvc.perform(post("/ai-insights/feedback").with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(rating(turnId))).andExpect(status().isForbidden());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        ChatModel chatModel(ObservationRegistry registry) {
            return new ScriptedChatModel(registry, prompt -> List.of(ScriptedChatModel.text("answer", 20, 10)));
        }

        @Bean
        SecurityFilterChain appChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .build();
        }

        @Bean
        ChatController chatController(ChatClient.Builder builder) {
            return new ChatController(builder.build());
        }
    }

    @RestController
    static class ChatController {

        private final ChatClient chat;

        ChatController(ChatClient chat) {
            this.chat = chat;
        }

        @GetMapping("/chat")
        String chat() {
            return (String) chat.prompt().user("hi").call().chatClientResponse().context()
                .get(InsightsContextKeys.TURN_ID);
        }
    }
}
