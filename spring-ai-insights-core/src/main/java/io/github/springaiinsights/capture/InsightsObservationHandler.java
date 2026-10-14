package io.github.springaiinsights.capture;

import java.util.ArrayList;
import java.util.List;

import io.github.springaiinsights.metrics.InsightsMetrics;
import io.github.springaiinsights.model.RetrievalRecord;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;

/**
 * Attaches model calls, tool calls and vector-store queries to the turn they belong to, found by walking the
 * observation parent chain. Never throws: failures are counted in {@code ai.insights.internal.errors}.
 */
public final class InsightsObservationHandler implements ObservationHandler<Observation.Context> {

    private static final String START_NANOS_KEY = InsightsObservationHandler.class.getName() + ".start";

    private static final String RECORDER_KEY = InsightsObservationHandler.class.getName() + ".recorder";

    private static final String UNKNOWN = "unknown";

    private final InsightsMetrics metrics;

    public InsightsObservationHandler(InsightsMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatModelObservationContext || context instanceof ToolCallingObservationContext
                || context instanceof VectorStoreObservationContext;
    }

    @Override
    public void onStart(Observation.Context context) {
        context.put(START_NANOS_KEY, System.nanoTime());
        if (context instanceof ChatModelObservationContext) {
            try {
                TurnRecorder recorder = TurnRecorders.outermost(context);
                if (recorder != null) {
                    recorder.beginModelCall();
                    context.put(RECORDER_KEY, recorder);
                }
            }
            catch (RuntimeException e) {
                metrics.internalError();
            }
        }
    }

    @Override
    public void onStop(Observation.Context context) {
        if (context instanceof ChatModelObservationContext chat) {
            TurnRecorder recorder = context.get(RECORDER_KEY);
            if (recorder == null) {
                return;
            }
            try {
                recordModelCall(recorder, chat, durationMs(context));
            }
            catch (RuntimeException e) {
                metrics.internalError();
            }
            finally {
                endModelCall(recorder);
            }
            return;
        }
        try {
            TurnRecorder recorder = TurnRecorders.outermost(context);
            if (recorder == null) {
                return;
            }
            long durationMs = durationMs(context);
            if (context instanceof ToolCallingObservationContext tool) {
                recorder.addToolCall(tool.getToolDefinition().name(), durationMs, tool.getError());
            }
            else if (context instanceof VectorStoreObservationContext store) {
                recordRetrieval(recorder, store, durationMs);
            }
        }
        catch (RuntimeException e) {
            metrics.internalError();
        }
    }

    private void endModelCall(TurnRecorder recorder) {
        try {
            recorder.endModelCall();
        }
        catch (RuntimeException e) {
            metrics.internalError();
        }
    }

    private static void recordModelCall(TurnRecorder recorder, ChatModelObservationContext chat, long durationMs) {
        ChatResponse response = chat.getResponse();
        ChatResponseMetadata metadata = response == null ? null : response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        String model = metadata == null ? null : metadata.getModel();
        if (isBlank(model)) {
            model = chat.getRequest().getOptions() == null ? null : chat.getRequest().getOptions().getModel();
        }
        recorder.addModelCall(chat.getOperationMetadata().provider(), isBlank(model) ? UNKNOWN : model,
                usage == null ? null : usage.getPromptTokens(), usage == null ? null : usage.getCompletionTokens(),
                usage == null ? null : usage.getCacheReadInputTokens(),
                usage == null ? null : usage.getCacheWriteInputTokens(), durationMs, chat.getError());
    }

    private static void recordRetrieval(TurnRecorder recorder, VectorStoreObservationContext store, long durationMs) {
        if (!VectorStoreObservationContext.Operation.QUERY.value().equals(store.getOperationName())) {
            return;
        }
        List<Document> documents = store.getQueryResponse() == null ? List.of() : store.getQueryResponse();
        List<Double> scores = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Document document : documents) {
            if (document.getScore() != null) {
                scores.add(document.getScore());
            }
            ids.add(document.getId());
        }
        Integer topK = store.getQueryRequest() == null ? null : store.getQueryRequest().getTopK();
        recorder.addRetrieval(new RetrievalRecord(store.getDatabaseSystem(), topK, documents.size(), scores, ids,
                durationMs));
    }

    private static long durationMs(Observation.Context context) {
        Long start = context.get(START_NANOS_KEY);
        return start == null ? 0 : Math.max(0, (System.nanoTime() - start) / 1_000_000L);
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
