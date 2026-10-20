package io.github.bakic.springaiinsights.demo;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import io.github.bakic.springaiinsights.identity.InsightsContextKeys;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Component;

/** The helpdesk assistant: help-center retrieval, three tools, and the context Spring AI Insights records. */
@Component
class HelpdeskAssistant {

    record Answer(String text, String turnId) {
    }

    private static final String SYSTEM = """
            You are the helpdesk assistant of an online store. Answer from the help center or with your tools.
            Be short and concrete.""";

    private final ChatClient chat;

    HelpdeskAssistant(ChatClient.Builder builder, SimpleVectorStore helpCenter, HelpdeskTools tools) {
        this.chat = builder.defaultSystem(SYSTEM)
            .defaultTools(tools)
            .defaultAdvisors(QuestionAnswerAdvisor.builder(helpCenter)
                .searchRequest(SearchRequest.builder()
                    .topK(HelpCenter.TOP_K)
                    .similarityThreshold(HelpCenter.SIMILARITY_THRESHOLD)
                    .build())
                .build())
            .build();
    }

    Answer ask(String userId, String conversationId, String feature, String question) {
        ChatClientResponse response = prompt(userId, conversationId, feature, question).call().chatClientResponse();
        return new Answer(text(response), turnId(response));
    }

    /** Streams the answer, as a chat UI would, and returns it once complete. */
    Answer askStreaming(String userId, String conversationId, String feature, String question) {
        List<ChatClientResponse> chunks = prompt(userId, conversationId, feature, question).stream()
            .chatClientResponse()
            .collectList()
            .block(Duration.ofSeconds(30));
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalStateException("empty stream");
        }
        String text = chunks.stream().map(HelpdeskAssistant::text).filter(Objects::nonNull).collect(Collectors.joining());
        return new Answer(text, turnId(chunks.get(0)));
    }

    private ChatClient.ChatClientRequestSpec prompt(String userId, String conversationId, String feature,
            String question) {
        return chat.prompt()
            .user(question)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId)
                .param(InsightsContextKeys.USER_ID, userId)
                .param(InsightsContextKeys.ATTRIBUTE_PREFIX + "feature", feature));
    }

    private static String text(ChatClientResponse response) {
        ChatResponse chat = response.chatResponse();
        return chat == null || chat.getResult() == null ? null : chat.getResult().getOutput().getText();
    }

    private static String turnId(ChatClientResponse response) {
        return (String) response.context().get(InsightsContextKeys.TURN_ID);
    }
}
