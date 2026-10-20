package io.github.bakic.springaiinsights.web.pages;

import java.util.List;

import io.github.bakic.springaiinsights.feedback.Feedback;
import io.github.bakic.springaiinsights.model.ConversationTurn;

/**
 * The turns of one conversation and the feedback on them, oldest first. A conversation too long to show has its
 * latest turns here; {@code totalTurns} is how many it has in all, so the page can say that it is cut.
 */
public record ConversationPage(List<ConversationTurn> turns, List<Feedback> feedback, long totalTurns) {

    public ConversationPage {
        turns = List.copyOf(turns);
        feedback = List.copyOf(feedback);
    }
}
