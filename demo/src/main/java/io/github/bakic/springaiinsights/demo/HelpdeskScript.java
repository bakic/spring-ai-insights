package io.github.bakic.springaiinsights.demo;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Decides what the simulated assistant says: a tool call when the question asks for one, otherwise an answer
 * built from the tool result or from the help-center context that {@code QuestionAnswerAdvisor} added.
 */
final class HelpdeskScript {

    static final String TOOL_MODEL = "gpt-4o";

    static final String CHAT_MODEL = "gpt-4o-mini";

    static final String UNKNOWN_ORDER = "I couldn't find that order.";

    static final String NO_ANSWER = "I can't answer that from our help center.";

    // QuestionAnswerAdvisor's default template: the question, then the context between two rules.
    private static final String CONTEXT_MARKER = "\n\nContext information is below";

    private static final String RULE = "---------------------\n";

    private static final Pattern ORDER = Pattern.compile("(?:order\\D{0,12}|#)(\\d{3,})");

    private static final Pattern OUTAGE = Pattern.compile("\\b(down|status|slow)\\b");

    private static final Pattern HANDOFF = Pattern.compile("\\b(human|agent|complaint)\\b");

    private static final List<String> SERVICES = List.of("checkout", "login", "payments", "search", "website");

    ChatResponse respond(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        String userText = lastUserText(messages);
        String question = question(userText);
        boolean usesTool = tool(question) != null;
        String model = usesTool ? TOOL_MODEL : CHAT_MODEL;
        int inputTokens = Math.max(1, messages.stream().mapToInt(m -> length(m)).sum() / 4);

        ToolResponseMessage toolResult = lastToolResult(messages);
        if (usesTool && toolResult == null) {
            var call = new AssistantMessage.ToolCall("call-" + Integer.toHexString(question.hashCode()), "function",
                    tool(question), arguments(question));
            var message = AssistantMessage.builder().content("").toolCalls(List.of(call)).build();
            return response(message, "TOOL_CALLS", model, inputTokens, 12);
        }
        String answer = toolResult != null ? fromTool(toolResult) : fromContext(context(userText));
        return response(new AssistantMessage(answer), "STOP", model, inputTokens, Math.max(1, answer.length() / 4));
    }

    static String question(String userText) {
        int marker = userText.indexOf(CONTEXT_MARKER);
        return (marker < 0 ? userText : userText.substring(0, marker)).strip();
    }

    private static String context(String userText) {
        int marker = userText.indexOf(CONTEXT_MARKER);
        if (marker < 0) {
            return "";
        }
        int open = userText.indexOf(RULE, userText.indexOf(RULE, marker) + RULE.length());
        if (open < 0) {
            return "";
        }
        int start = open + RULE.length();
        // The renderer drops the empty line of an empty context, so the closing rule can follow the opening one.
        int end = userText.indexOf(RULE, start);
        return (end < 0 ? userText.substring(start) : userText.substring(start, end)).strip();
    }

    private static String tool(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        if (ORDER.matcher(q).find()) {
            return "lookupOrder";
        }
        if (OUTAGE.matcher(q).find()) {
            return "serviceStatus";
        }
        if (HANDOFF.matcher(q).find()) {
            return "openTicket";
        }
        return null;
    }

    private static String arguments(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        Matcher order = ORDER.matcher(q);
        if (order.find()) {
            return "{\"orderNumber\":\"" + order.group(1) + "\"}";
        }
        if ("serviceStatus".equals(tool(question))) {
            String service = SERVICES.stream().filter(q::contains).findFirst().orElse("website");
            return "{\"service\":\"" + service + "\"}";
        }
        return "{\"summary\":\"" + json(question) + "\"}";
    }

    private static String fromTool(ToolResponseMessage result) {
        String data = result.getResponses().get(result.getResponses().size() - 1).responseData().strip();
        if (data.length() >= 2 && data.startsWith("\"") && data.endsWith("\"")) {
            data = data.substring(1, data.length() - 1);
        }
        if (data.contains("Unknown order")) {
            return UNKNOWN_ORDER + " Please check the number on your confirmation email (" + data + ").";
        }
        return "Here is what I found: " + data;
    }

    private static String fromContext(String context) {
        if (context.isEmpty()) {
            return NO_ANSWER + " Ask me about orders, refunds, shipping or your account.";
        }
        String firstParagraph = Arrays.stream(context.split("\n\\s*\n"))
            .map(String::strip)
            .filter(paragraph -> !paragraph.startsWith("#"))
            .findFirst()
            .orElse(context.strip());
        return "From our help center: " + firstParagraph.replace('\n', ' ');
    }

    private static ChatResponse response(AssistantMessage message, String finishReason, String model, int inputTokens,
            int outputTokens) {
        return ChatResponse.builder()
            .generations(List.of(new Generation(message, ChatGenerationMetadata.builder().finishReason(finishReason).build())))
            .metadata(ChatResponseMetadata.builder().model(model).usage(new DefaultUsage(inputTokens, outputTokens)).build())
            .build();
    }

    private static String lastUserText(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage user) {
                return user.getText() == null ? "" : user.getText();
            }
        }
        return "";
    }

    private static ToolResponseMessage lastToolResult(List<Message> messages) {
        Message last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        return last instanceof ToolResponseMessage result ? result : null;
    }

    private static int length(Message message) {
        if (message instanceof ToolResponseMessage result) {
            return result.getResponses().stream().mapToInt(r -> r.responseData().length()).sum();
        }
        return message.getText() == null ? 0 : message.getText().length();
    }

    private static String json(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    }
                    else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
