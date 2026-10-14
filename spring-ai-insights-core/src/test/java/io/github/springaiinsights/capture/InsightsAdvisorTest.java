package io.github.springaiinsights.capture;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import io.github.springaiinsights.ScriptedChatModel;
import io.github.springaiinsights.identity.InsightsContextKeys;
import io.github.springaiinsights.identity.InsightsIdentity;
import io.github.springaiinsights.identity.InsightsIdentityResolver;
import io.github.springaiinsights.identity.Pseudonymiser;
import io.github.springaiinsights.metrics.InsightsMetrics;
import io.github.springaiinsights.model.ConversationTurn;
import io.github.springaiinsights.model.TurnOutcome;
import io.github.springaiinsights.store.AsyncEventWriter;
import io.github.springaiinsights.store.InMemoryEventStore;
import io.github.springaiinsights.store.TurnQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.DefaultAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static io.github.springaiinsights.ScriptedChatModel.text;
import static io.github.springaiinsights.ScriptedChatModel.toolCall;
import static io.github.springaiinsights.ScriptedChatModel.toolResponses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InsightsAdvisorTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final InsightsMetrics metrics = new InsightsMetrics(meters, "app", false);

    private final ObservationRegistry registry = ObservationRegistry.create();

    private final InMemoryEventStore store = new InMemoryEventStore(100);

    private final AsyncEventWriter writer = new AsyncEventWriter(store, 100, metrics);

    private InsightsIdentityResolver resolver = request -> new InsightsIdentity(null, null);

    private Pseudonymiser pseudonymiser = null;

    private boolean captureContent = false;

    InsightsAdvisorTest() {
        registry.observationConfig().observationHandler(new InsightsObservationHandler(metrics));
    }

    @AfterEach
    void close() {
        writer.close();
    }

    private InsightsAdvisor advisor() {
        return new InsightsAdvisor(registry, resolver, pseudonymiser, TurnRecorderTest.costs(), writer, metrics, "app",
                captureContent);
    }

    private ChatClient client(ScriptedChatModel model) {
        return ChatClient.builder(model, registry, null, null).defaultAdvisors(advisor()).build();
    }

    private ChatClient client(Function<Prompt, List<ChatResponse>> script) {
        return client(new ScriptedChatModel(registry, script));
    }

    private List<ConversationTurn> turns() {
        return store.findTurns(new TurnQuery("app", java.time.Instant.EPOCH, java.time.Instant.now().plusSeconds(60),
                null, 100));
    }

    private ConversationTurn awaitTurn() {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            List<ConversationTurn> turns = turns();
            if (!turns.isEmpty()) {
                return turns.get(0);
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("no turn recorded");
    }

    private static ToolCallback tool(String name, Function<String, String> body) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{\"type\":\"object\"}").build();
            }

            @Override
            public String call(String toolInput) {
                return body.apply(toolInput);
            }
        };
    }

    @Test
    void callRecordsTurnWithTokensCostAndTurnId() {
        var resp = client(p -> List.of(text("hello", 100, 20))).prompt()
            .user("hi")
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "c1"))
            .call()
            .chatClientResponse();
        var turn = awaitTurn();
        assertThat(turn.conversationId()).isEqualTo("c1");
        assertThat(turn.conversationIdMissing()).isFalse();
        assertThat(turn.outcome()).isEqualTo(TurnOutcome.SUCCESS);
        assertThat(turn.streaming()).isFalse();
        assertThat(turn.modelCalls()).singleElement().satisfies(mc -> {
            assertThat(mc.inputTokens()).isEqualTo(100);
            assertThat(mc.provider()).isEqualTo("scripted");
        });
        assertThat(turn.totalCost()).isNotNull();
        assertThat(turn.finishReasons()).containsExactly("STOP");
        assertThat(resp.context()).containsEntry(InsightsContextKeys.TURN_ID, turn.turnId());
        assertThat(meters.get("ai.insights.turns").counter().count()).isEqualTo(1);
    }

    @Test
    void missingConversationIdIsGeneratedAndFlagged() {
        client(p -> List.of(text("hello", 1, 1))).prompt().user("hi").call().content();
        var turn = awaitTurn();
        assertThat(turn.conversationIdMissing()).isTrue();
        assertThat(turn.conversationId()).isNotBlank();
    }

    @Test
    void streamRecordsTtftAndSuccess() {
        var model = new ScriptedChatModel(registry,
                p -> List.of(ScriptedChatModel.chunk("a"), ScriptedChatModel.chunk("b"), text("c", 30, 3)))
            .chunkDelay(Duration.ofMillis(50));
        StepVerifier.create(client(model).prompt().user("hi").stream().content())
            .expectNext("a", "b", "c")
            .verifyComplete();
        var turn = awaitTurn();
        assertThat(turn.outcome()).isEqualTo(TurnOutcome.SUCCESS);
        assertThat(turn.streaming()).isTrue();
        assertThat(turn.timeToFirstTokenMs()).isPositive();
        assertThat(turn.timeToFirstTokenMs()).isLessThan(turn.latencyMs());
        assertThat(turn.modelCalls()).singleElement().satisfies(mc -> {
            assertThat(mc.provider()).isEqualTo("scripted");
            assertThat(mc.inputTokens()).isEqualTo(30);
        });
    }

    @Test
    void streamWaitsForModelCallReportedAfterStreamEnds() {
        // ChatModelStreamAdvisor publishes on another thread, so the provider may stop its observation after the
        // client has already seen the end of the stream.
        var model = new ScriptedChatModel(registry, p -> List.of(ScriptedChatModel.chunk("a"), text("b", 30, 3)))
            .stopDelay(Duration.ofMillis(200));
        client(model).prompt()
            .user("hi")
            .stream()
            .content()
            .blockLast();
        assertThat(awaitTurn().modelCalls()).singleElement().satisfies(mc -> {
            assertThat(mc.provider()).isEqualTo("scripted");
            assertThat(mc.inputTokens()).isEqualTo(30);
            assertThat(mc.durationMs()).isGreaterThanOrEqualTo(200);
        });
    }

    @Test
    void streamResponsesCarryTurnId() {
        var responses = client(p -> List.of(text("x", 1, 1))).prompt().user("hi").stream().chatClientResponse()
            .collectList()
            .block();
        var turn = awaitTurn();
        assertThat(responses).isNotEmpty()
            .allSatisfy(r -> assertThat(r.context()).containsEntry(InsightsContextKeys.TURN_ID, turn.turnId()));
    }

    @Test
    void streamCancelledRecordsCancelledTurn() {
        var model = new ScriptedChatModel(registry,
                p -> List.of(ScriptedChatModel.chunk("a"), ScriptedChatModel.chunk("b"), text("c", 1, 1)))
            .chunkDelay(Duration.ofMillis(50));
        StepVerifier.create(client(model).prompt().user("hi").stream().content())
            .expectNextCount(1)
            .thenCancel()
            .verify();
        var turn = awaitTurn();
        assertThat(turn.outcome()).isEqualTo(TurnOutcome.CANCELLED);
        assertThat(turn.modelCalls()).hasSize(1);
    }

    @Test
    void streamCancelledKeepsUsageSeenBeforeCancel() {
        var model = new ScriptedChatModel(registry,
                p -> List.of(text("a", 30, 0), ScriptedChatModel.chunk("b"), text("c", 30, 9)))
            .chunkDelay(Duration.ofMillis(50));
        StepVerifier.create(client(model).prompt().user("hi").stream().content())
            .expectNextCount(1)
            .thenCancel()
            .verify();
        var turn = awaitTurn();
        assertThat(turn.outcome()).isEqualTo(TurnOutcome.CANCELLED);
        assertThat(turn.modelCalls()).singleElement().satisfies(call -> {
            assertThat(call.provider()).isEqualTo("scripted");
            assertThat(call.inputTokens()).isEqualTo(30);
            assertThat(call.outputTokens()).isZero();
        });
    }

    @Test
    void callWithNullResponseFromChainDoesNotThrow() {
        var advisor = advisor();
        ChatClientRequest request = ChatClientRequest.builder().prompt(new Prompt("hi")).build();
        CallAdvisorChain chain = DefaultAroundAdvisorChain.builder(registry)
            .pushAll(List.of(advisor, new CallAdvisor() {
                @Override
                public ChatClientResponse adviseCall(ChatClientRequest r, CallAdvisorChain c) {
                    return null;
                }

                @Override
                public String getName() {
                    return "null-returning";
                }

                @Override
                public int getOrder() {
                    return 0;
                }
            }))
            .build();

        assertThat(chain.nextCall(request)).isNull();
    }

    @Test
    void modelErrorRecordsErrorAndRethrows() {
        var boom = new IllegalStateException("throttled");
        assertThatThrownBy(() -> client(p -> {
            throw boom;
        }).prompt().user("hi").call().content()).isSameAs(boom);
        var turn = awaitTurn();
        assertThat(turn.outcome()).isEqualTo(TurnOutcome.ERROR);
        assertThat(turn.errorType()).isEqualTo("IllegalStateException");
        assertThat(turn.modelCalls()).singleElement().satisfies(mc -> assertThat(mc.errorType()).isEqualTo("IllegalStateException"));
    }

    @Test
    void streamErrorRecordsErrorTurn() {
        StepVerifier.create(client(p -> {
            throw new IllegalStateException("down");
        }).prompt().user("hi").stream().content()).verifyError(IllegalStateException.class);
        assertThat(awaitTurn().outcome()).isEqualTo(TurnOutcome.ERROR);
    }

    @Test
    void toolLoopIsOneTurn() {
        var answer = client(p -> toolResponses(p) < 2 ? List.of(toolCall("lookup", "{}")) : List.of(text("done", 50, 5)))
            .prompt()
            .user("hi")
            .tools(tool("lookup", in -> "result"))
            .call()
            .content();
        assertThat(answer).isEqualTo("done");
        var turn = awaitTurn();
        assertThat(turns()).hasSize(1);
        assertThat(turn.modelCalls()).hasSize(3);
        assertThat(turn.toolCalls()).hasSize(2);
        assertThat(turn.toolCalls().get(1).repeatIndex()).isEqualTo(1);
    }

    @Test
    void streamToolLoopIsOneTurn() {
        var answer = client(p -> toolResponses(p) < 1 ? List.of(toolCall("lookup", "{}")) : List.of(text("done", 50, 5)))
            .prompt()
            .user("hi")
            .tools(tool("lookup", in -> "result"))
            .stream()
            .content()
            .collectList()
            .block();
        assertThat(String.join("", answer)).isEqualTo("done");
        var turn = awaitTurn();
        assertThat(turns()).hasSize(1);
        assertThat(turn.modelCalls()).hasSize(2);
        assertThat(turn.toolCalls()).hasSize(1);
    }

    @Test
    void nestedChatClientCallFoldsIntoOuterTurn() {
        var inner = client(p -> List.of(text("inner", 5, 5)));
        var outer = client(p -> toolResponses(p) < 1 ? List.of(toolCall("ask", "{}")) : List.of(text("outer", 7, 7)));
        outer.prompt().user("hi").tools(tool("ask", in -> inner.prompt().user("sub").call().content())).call().content();
        var turn = awaitTurn();
        assertThat(turns()).hasSize(1);
        assertThat(turn.modelCalls()).hasSize(3);
        assertThat(turn.toolCalls()).hasSize(1);
    }

    @Test
    void contentNotCapturedByDefault() {
        client(p -> List.of(text("answer", 1, 1))).prompt().user("hi").call().content();
        var turn = awaitTurn();
        assertThat(turn.userMessage()).isNull();
        assertThat(turn.assistantMessage()).isNull();
    }

    @Test
    void contentCapturedWhenEnabled() {
        captureContent = true;
        client(p -> List.of(text("answer", 1, 1))).prompt().user("hi").call().content();
        var turn = awaitTurn();
        assertThat(turn.userMessage()).isEqualTo("hi");
        assertThat(turn.assistantMessage()).isEqualTo("answer");
    }

    @Test
    void streamContentCapturedWhenEnabled() {
        captureContent = true;
        client(p -> List.of(ScriptedChatModel.chunk("ans"), text("wer", 1, 1))).prompt().user("hi").stream().content()
            .blockLast();
        assertThat(awaitTurn().assistantMessage()).isEqualTo("answer");
    }

    @Test
    void resolverFailureDoesNotBreakChat() {
        resolver = request -> {
            throw new IllegalStateException("resolver bug");
        };
        var answer = client(p -> List.of(text("answer", 1, 1))).prompt().user("hi").call().content();
        assertThat(answer).isEqualTo("answer");
        assertThat(meters.get("ai.insights.internal.errors").counter().count()).isEqualTo(1);
    }

    @Test
    void userIdIsPseudonymised() {
        pseudonymiser = new Pseudonymiser("k".getBytes());
        resolver = request -> new InsightsIdentity("alice", "acme");
        client(p -> List.of(text("answer", 1, 1))).prompt().user("hi").call().content();
        var turn = awaitTurn();
        assertThat(turn.userId()).isEqualTo(pseudonymiser.pseudonymise("alice"));
        assertThat(turn.tenantId()).isEqualTo("acme");
    }

    @Test
    void attributesFromContextPrefix() {
        client(p -> List.of(text("answer", 1, 1))).prompt()
            .user("hi")
            .advisors(a -> a.param("ai-insights.attr.feature", "faq").param("other", "x"))
            .call()
            .content();
        assertThat(awaitTurn().attributes()).containsExactly(java.util.Map.entry("feature", "faq"));
    }

    @Test
    void uncorrelatedClientStillRecordsUsageFromResponse() {
        var plain = ChatClient.builder(new ScriptedChatModel(ObservationRegistry.NOOP, p -> List.of(text("x", 40, 4))))
            .defaultAdvisors(advisor())
            .build();
        plain.prompt().user("hi").call().content();
        var turn = awaitTurn();
        assertThat(turn.modelCalls()).singleElement().satisfies(mc -> {
            assertThat(mc.provider()).isEqualTo("unknown");
            assertThat(mc.inputTokens()).isEqualTo(40);
        });
    }

    @Test
    void advisorIsOutsideMemoryAndToolLoop() {
        var advisor = advisor();
        assertThat(advisor.getName()).isEqualTo("ai-insights");
        assertThat(advisor.getOrder()).isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 100);
    }
}
