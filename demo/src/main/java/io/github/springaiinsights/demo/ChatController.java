package io.github.springaiinsights.demo;

import java.security.Principal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/chat}: asks the assistant as the signed-in user. */
@RestController
class ChatController {

    record ChatRequest(String conversationId, String question) {
    }

    private final HelpdeskAssistant assistant;

    ChatController(HelpdeskAssistant assistant) {
        this.assistant = assistant;
    }

    @PostMapping("/api/chat")
    ResponseEntity<HelpdeskAssistant.Answer> chat(@RequestBody ChatRequest request, Principal user) {
        if (request.question() == null || request.question().isBlank() || request.conversationId() == null
                || request.conversationId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(assistant.ask(user.getName(), request.conversationId(), "chat", request.question()));
    }
}
