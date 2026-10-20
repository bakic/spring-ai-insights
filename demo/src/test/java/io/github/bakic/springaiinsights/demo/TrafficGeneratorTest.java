package io.github.bakic.springaiinsights.demo;

import java.util.ArrayList;
import java.util.List;

import io.github.bakic.springaiinsights.feedback.InsightsFeedback;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrafficGeneratorTest {

    @Test
    void oneFailingTurnDoesNotStopTraffic() {
        List<String> asked = new ArrayList<>();
        Answer<HelpdeskAssistant.Answer> failsOnce = call -> {
            asked.add(call.getArgument(3));
            if (asked.size() == 1) {
                throw new IllegalStateException("model unavailable");
            }
            return new HelpdeskAssistant.Answer("fine", "turn-" + asked.size());
        };
        HelpdeskAssistant assistant = mock(HelpdeskAssistant.class);
        when(assistant.ask(anyString(), anyString(), anyString(), anyString())).thenAnswer(failsOnce);
        when(assistant.askStreaming(anyString(), anyString(), anyString(), anyString())).thenAnswer(failsOnce);
        TrafficGenerator generator = new TrafficGenerator(assistant, mock(InsightsFeedback.class), 42);

        generator.tick();
        generator.tick();

        assertThat(asked).hasSize(2);
    }

    @Test
    void sameSeedSameQuestions() {
        assertThat(questions(7)).isEqualTo(questions(7)).isNotEqualTo(questions(8));
    }

    @Test
    void helpCenterQuestionsFindAnArticleAndOffTopicOnesDoNot() {
        SimpleVectorStore store = new HelpCenter().helpCenter(ObservationRegistry.NOOP);
        for (TrafficGenerator.Question question : TrafficGenerator.QUESTIONS) {
            List<Document> found = store.similaritySearch(SearchRequest.builder()
                .query(question.text())
                .topK(HelpCenter.TOP_K)
                .similarityThreshold(HelpCenter.SIMILARITY_THRESHOLD)
                .build());
            if (question.feature().equals("off-topic")) {
                assertThat(found).as(question.text()).isEmpty();
            }
            else if (question.article() != null) {
                assertThat(found).as(question.text()).extracting(Document::getId).first().isEqualTo(question.article());
            }
        }
    }

    private static List<String> questions(long seed) {
        List<String> asked = new ArrayList<>();
        TrafficGenerator generator = new TrafficGenerator(recording(asked), mock(InsightsFeedback.class), seed);
        for (int i = 0; i < 30; i++) {
            generator.tick();
        }
        assertThat(asked).hasSize(30);
        return asked;
    }

    private static HelpdeskAssistant recording(List<String> asked) {
        HelpdeskAssistant assistant = mock(HelpdeskAssistant.class);
        when(assistant.ask(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            asked.add(call.getArgument(3));
            return new HelpdeskAssistant.Answer("fine", "turn-" + asked.size());
        });
        when(assistant.askStreaming(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            asked.add(call.getArgument(3));
            return new HelpdeskAssistant.Answer("fine", "turn-" + asked.size());
        });
        return assistant;
    }
}
