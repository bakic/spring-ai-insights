package io.github.bakic.springaiinsights.feedback;

/** Storage SPI for feedback. Implementations must be thread-safe. */
public interface FeedbackStore {

    /** Saves the feedback, replacing any earlier feedback on the same turn. */
    void saveFeedback(Feedback feedback);
}
