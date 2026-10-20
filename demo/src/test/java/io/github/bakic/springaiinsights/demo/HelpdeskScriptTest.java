package io.github.bakic.springaiinsights.demo;

import java.util.List;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.SearchRequest;

import static org.assertj.core.api.Assertions.assertThat;

class HelpdeskScriptTest {

    private final HelpdeskScript script = new HelpdeskScript();

    @Test
    void orderQuestionCallsLookupOrder() {
        ChatResponse response = script.respond(new Prompt(new UserMessage("Where is my order #10423?")));

        AssistantMessage.ToolCall call = toolCall(response);
        assertThat(call.name()).isEqualTo("lookupOrder");
        assertThat(call.arguments()).isEqualTo("{\"orderNumber\":\"10423\"}");
        assertThat(response.getMetadata().getModel()).isEqualTo("gpt-4o");
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
    }

    @Test
    void outageQuestionCallsServiceStatus() {
        ChatResponse response = script.respond(new Prompt(new UserMessage("Is checkout down right now?")));

        AssistantMessage.ToolCall call = toolCall(response);
        assertThat(call.name()).isEqualTo("serviceStatus");
        assertThat(call.arguments()).isEqualTo("{\"service\":\"checkout\"}");
    }

    @Test
    void humanRequestOpensTicket() {
        ChatResponse response = script.respond(new Prompt(new UserMessage("I want to talk to a human")));

        AssistantMessage.ToolCall call = toolCall(response);
        assertThat(call.name()).isEqualTo("openTicket");
        assertThat(call.arguments()).isEqualTo("{\"summary\":\"I want to talk to a human\"}");
    }

    @Test
    void keywordsMatchWholeWordsOnly() {
        ChatResponse response = script.respond(new Prompt(new UserMessage("Where can I download the invoice for my order?")));

        assertThat(response.getResult().getOutput().hasToolCalls()).isFalse();
    }

    @Test
    void afterAToolResultItAnswers() {
        ChatResponse first = script.respond(new Prompt(new UserMessage("Where is my order #10423?")));
        AssistantMessage.ToolCall call = toolCall(first);
        var result = ToolResponseMessage.builder()
            .responses(List.of(new ToolResponseMessage.ToolResponse(call.id(), call.name(), "\"Order 10423: shipped on May 2\"")))
            .build();

        ChatResponse response = script.respond(
                new Prompt(List.of(new UserMessage("Where is my order #10423?"), first.getResult().getOutput(), result)));

        assertThat(response.getResult().getOutput().hasToolCalls()).isFalse();
        assertThat(response.getResult().getOutput().getText()).contains("Order 10423: shipped on May 2");
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
        assertThat(response.getMetadata().getModel()).isEqualTo("gpt-4o");
    }

    @Test
    void answersFromTheArticleTheAdvisorRetrieved() {
        String answer = askThroughTheAdvisor("How do I cancel my subscription?");

        assertThat(answer).startsWith("From our help center: Cancel your subscription any time from Account");
    }

    @Test
    void answersNoAnswerWhenTheAdvisorRetrievedNothing() {
        String answer = askThroughTheAdvisor("Write a poem about cats");

        assertThat(answer).startsWith(HelpdeskScript.NO_ANSWER);
    }

    @Test
    void retrievedContextIsAnsweredWithTheChatModel() {
        String augmented = "How long do refunds take?\n\nContext information is below, surrounded by ---------------------\n\n"
                + "---------------------\nRefunds reach your card within 5 business days.\n---------------------\n\nGiven the context";

        ChatResponse response = script.respond(new Prompt(new UserMessage(augmented)));

        assertThat(response.getResult().getOutput().getText())
            .isEqualTo("From our help center: Refunds reach your card within 5 business days.");
        assertThat(response.getMetadata().getModel()).isEqualTo("gpt-4o-mini");
    }

    // The real advisor, so the test sees the prompt exactly as QuestionAnswerAdvisor renders it.
    private String askThroughTheAdvisor(String question) {
        ChatModel model = prompt -> script.respond(prompt);
        var advisor = QuestionAnswerAdvisor.builder(new HelpCenter().helpCenter(ObservationRegistry.NOOP))
            .searchRequest(SearchRequest.builder().topK(HelpCenter.TOP_K).similarityThreshold(HelpCenter.SIMILARITY_THRESHOLD).build())
            .build();
        return ChatClient.builder(model).defaultAdvisors(advisor).build().prompt().user(question).call().content();
    }

    @Test
    void usageIsDerivedFromLength() {
        String question = "q".repeat(400);

        ChatResponse response = script.respond(new Prompt(new UserMessage(question)));

        String answer = response.getResult().getOutput().getText();
        assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(100);
        assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(Math.max(1, answer.length() / 4));
    }

    private static AssistantMessage.ToolCall toolCall(ChatResponse response) {
        assertThat(response.getResult().getOutput().getToolCalls()).hasSize(1);
        return response.getResult().getOutput().getToolCalls().get(0);
    }
}
