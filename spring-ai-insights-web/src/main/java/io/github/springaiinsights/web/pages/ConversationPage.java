package io.github.springaiinsights.web.pages;

import java.util.List;

import io.github.springaiinsights.feedback.Feedback;
import io.github.springaiinsights.model.ConversationTurn;

/** The turns of one conversation and the feedback on them, oldest first. */
public record ConversationPage(List<ConversationTurn> turns, List<Feedback> feedback) {

    public ConversationPage {
        turns = List.copyOf(turns);
        feedback = List.copyOf(feedback);
    }
}
