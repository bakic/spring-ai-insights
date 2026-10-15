package io.github.springaiinsights.demo;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

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
    void answersFromRetrievedContext() {
        String augmented = "How long do refunds take?\n\nContext information is below, surrounded by ---------------------\n\n"
                + "---------------------\nRefunds reach your card within 5 business days.\n---------------------\n\nGiven the context";

        ChatResponse response = script.respond(new Prompt(new UserMessage(augmented)));

        assertThat(response.getResult().getOutput().getText()).contains("Refunds reach your card within 5 business days.");
        assertThat(response.getMetadata().getModel()).isEqualTo("gpt-4o-mini");
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
