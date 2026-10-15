package io.github.springaiinsights.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import io.github.springaiinsights.feedback.InsightsFeedback;
import io.github.springaiinsights.feedback.Rating;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Simulates twelve customers so the dashboard has something to show: each tick continues or starts a conversation,
 * asks one question, sometimes streams it and sometimes rates the answer. Seeded, so a run is repeatable.
 */
@Component
@ConditionalOnBooleanProperty("demo.traffic.enabled")
class TrafficGenerator {

    /** A question, the screen it is asked from, and the help-center article that answers it (if any). */
    record Question(String feature, String text, @Nullable String article) {
    }

    static final List<String> FEATURES = List.of("orders", "account", "billing", "outage");

    static final List<Question> QUESTIONS = List.of(
            new Question("orders", "Where is my order #10423?", null),
            new Question("orders", "Has order 10388 been delivered?", null),
            new Question("orders", "Can you track order #10512 for me?", null),
            new Question("orders", "What happened to order #10560?", null),
            new Question("orders", "When will order 10477 ship?", null),
            new Question("orders", "Where is my order #99012?", null),
            new Question("orders", "Order #20871 never arrived", null),
            new Question("orders", "How long does express shipping take?", "shipping-times.md"),
            new Question("orders", "How many business days does standard shipping take?", "shipping-times.md"),
            new Question("orders", "Can I change the delivery address of my order?", "change-address.md"),
            new Question("orders", "The parcel shipped already, can I redirect it to another address?", "change-address.md"),
            new Question("account", "How do I reset my password?", "password-reset.md"),
            new Question("account", "My password reset link expired", "password-reset.md"),
            new Question("account", "How do I cancel my subscription?", "cancel-subscription.md"),
            new Question("account", "Do I keep access after I cancel the subscription?", "cancel-subscription.md"),
            new Question("account", "What are the contact hours of your support team?", "contact-hours.md"),
            new Question("account", "Does support answer the phone on Saturday?", "contact-hours.md"),
            new Question("account", "I want to talk to a human", null),
            new Question("billing", "When does my refund reach my card?", "refunds.md"),
            new Question("billing", "How do I start a refund to get my money back?", "refunds.md"),
            new Question("billing", "Can I get a refund for a digital download?", "refunds.md"),
            new Question("billing", "Where can I download the invoice for my order?", "invoices.md"),
            new Question("billing", "How do I change the company name on an invoice?", "invoices.md"),
            new Question("billing", "My item is broken, does the warranty cover the defect?", "warranty.md"),
            new Question("billing", "I have a complaint about a double charge", null),
            new Question("outage", "Is checkout down?", null),
            new Question("outage", "The website is really slow today", null),
            new Question("outage", "Login is down for me", null),
            new Question("outage", "What is the payments status?", null),
            new Question("outage", "Search is slow, is something down?", null),
            new Question("outage", "Please connect me to an agent, checkout keeps failing", null),
            new Question("off-topic", "What is the capital of Peru?", null),
            new Question("off-topic", "Tell me a joke", null),
            new Question("off-topic", "Who won the football game last night?", null),
            new Question("off-topic", "Write a poem about cats", null),
            new Question("off-topic", "Recommend a good pizza recipe", null));

    private static final Logger log = LoggerFactory.getLogger(TrafficGenerator.class);

    private static final int PERSONAS = 12;

    private static final int MAX_OPEN_CONVERSATIONS = 6;

    private record Conversation(String id, String userId, String feature) {
    }

    private final HelpdeskAssistant assistant;

    private final InsightsFeedback feedback;

    private final Random random;

    private final List<Conversation> open = new ArrayList<>();

    private int started;

    TrafficGenerator(HelpdeskAssistant assistant, InsightsFeedback feedback, @Value("${demo.traffic.seed:42}") long seed) {
        this.assistant = assistant;
        this.feedback = feedback;
        this.random = new Random(seed);
    }

    @Scheduled(fixedDelayString = "${demo.traffic.interval:2s}")
    void tick() {
        try {
            turn();
        }
        catch (RuntimeException e) {
            log.warn("Demo traffic: a turn failed: {}", e.toString());
        }
    }

    private void turn() {
        // Every random draw happens before the call, so a failing call does not change what comes next.
        Conversation conversation = conversation();
        Question question = question(conversation.feature());
        boolean streamed = random.nextDouble() < 0.3;
        boolean rates = random.nextDouble() < 0.4;
        double mood = random.nextDouble();
        if (random.nextDouble() < 0.35) {
            open.remove(conversation);
        }

        HelpdeskAssistant.Answer answer = streamed
                ? assistant.askStreaming(conversation.userId(), conversation.id(), conversation.feature(), question.text())
                : assistant.ask(conversation.userId(), conversation.id(), conversation.feature(), question.text());

        if (rates && answer.turnId() != null) {
            rate(conversation, answer, mood);
        }
    }

    private Conversation conversation() {
        if (!open.isEmpty() && (open.size() >= MAX_OPEN_CONVERSATIONS || random.nextDouble() < 0.6)) {
            return open.get(random.nextInt(open.size()));
        }
        int persona = random.nextInt(PERSONAS) + 1;
        Conversation conversation = new Conversation("demo-%04d".formatted(++started), "user-%02d".formatted(persona),
                FEATURES.get((persona - 1) % FEATURES.size()));
        open.add(conversation);
        return conversation;
    }

    // Mostly questions from the customer's own screen, sometimes anything, including off-topic ones.
    private Question question(String feature) {
        List<Question> pool = random.nextDouble() < 0.7
                ? QUESTIONS.stream().filter(q -> q.feature().equals(feature)).toList() : QUESTIONS;
        return pool.get(random.nextInt(pool.size()));
    }

    private void rate(Conversation conversation, HelpdeskAssistant.Answer answer, double mood) {
        String comment;
        Rating rating;
        if (answer.text().startsWith(HelpdeskScript.UNKNOWN_ORDER)) {
            rating = Rating.DOWN;
            comment = "The order number is right, it is on my receipt";
        }
        else if (answer.text().startsWith(HelpdeskScript.NO_ANSWER)) {
            rating = Rating.DOWN;
            comment = "It did not answer my question";
        }
        else if (mood < 0.85) {
            rating = Rating.UP;
            comment = mood < 0.25 ? "Thanks, that helped" : null;
        }
        else {
            rating = Rating.DOWN;
            comment = "Too vague";
        }
        feedback.submit(answer.turnId(), conversation.userId(), rating, comment);
    }
}
