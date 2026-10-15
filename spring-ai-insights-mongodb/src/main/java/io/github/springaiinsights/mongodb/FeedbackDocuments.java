package io.github.springaiinsights.mongodb;

import java.util.Date;

import io.github.springaiinsights.feedback.Feedback;
import io.github.springaiinsights.feedback.Rating;
import org.bson.Document;

/** Maps feedback to BSON documents (schema version 1), keyed by turn ID so a new rating replaces the old one. */
final class FeedbackDocuments {

    static final int SCHEMA_VERSION = 1;

    private FeedbackDocuments() {
    }

    static Document toDocument(Feedback feedback) {
        Document doc = new Document("_id", feedback.turnId()).append("schemaVersion", SCHEMA_VERSION)
            .append("application", feedback.application())
            .append("conversationId", feedback.conversationId())
            .append("userId", feedback.userId())
            .append("tenantId", feedback.tenantId())
            .append("rating", feedback.rating().name())
            .append("createdAt", Date.from(feedback.createdAt()));
        if (feedback.comment() != null) {
            doc.append("comment", feedback.comment());
        }
        return doc;
    }

    static Feedback fromDocument(Document doc) {
        return new Feedback(doc.getString("_id"), doc.getString("application"), doc.getString("conversationId"),
                doc.getString("userId"), doc.getString("tenantId"), Rating.valueOf(doc.getString("rating")),
                doc.getString("comment"), doc.getDate("createdAt").toInstant());
    }
}
