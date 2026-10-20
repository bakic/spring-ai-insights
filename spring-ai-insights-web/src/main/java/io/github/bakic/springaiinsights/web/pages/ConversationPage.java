package io.github.bakic.springaiinsights.web.pages;

import java.util.List;

import io.github.bakic.springaiinsights.feedback.Feedback;
import io.github.bakic.springaiinsights.model.ConversationTurn;

/** The turns of one conversation and the feedback on them, oldest first. */
public record ConversationPage(List<ConversationTurn> turns, List<Feedback> feedback) {

    public ConversationPage {
        turns = List.copyOf(turns);
        feedback = List.copyOf(feedback);
    }
}
