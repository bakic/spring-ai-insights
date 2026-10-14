package io.github.springaiinsights.mongodb;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;

/**
 * Creates the indexes of the turns and feedback collections. When the retention changes, a TTL index is updated in
 * place, or dropped and recreated if the user may not run {@code collMod}.
 */
final class MongoIndexes {

    static final String TTL_INDEX = "ttl_startedAt";

    static final String FEEDBACK_TTL_INDEX = "ttl_createdAt";

    private static final int INDEX_OPTIONS_CONFLICT = 85;

    private static final int INDEX_KEY_SPECS_CONFLICT = 86;

    private MongoIndexes() {
    }

    static void ensure(MongoCollection<Document> turns, MongoCollection<Document> feedback, Duration retention,
            Consumer<Document> runCommand) {
        turns.createIndex(Indexes.compoundIndex(Indexes.ascending("application"), Indexes.descending("startedAt")),
                new IndexOptions().name("application_startedAt"));
        turns.createIndex(
                Indexes.compoundIndex(Indexes.ascending("application", "conversationId"),
                        Indexes.ascending("startedAt")),
                new IndexOptions().name("application_conversationId_startedAt"));
        turns.createIndex(
                Indexes.compoundIndex(Indexes.ascending("application", "tenantId"), Indexes.descending("startedAt")),
                new IndexOptions().name("application_tenantId_startedAt"));
        ttl(turns, "startedAt", TTL_INDEX, retention, runCommand);
        feedback.createIndex(
                Indexes.compoundIndex(Indexes.ascending("application"), Indexes.descending("createdAt")),
                new IndexOptions().name("application_createdAt"));
        feedback.createIndex(Indexes.ascending("application", "conversationId"),
                new IndexOptions().name("application_conversationId"));
        ttl(feedback, "createdAt", FEEDBACK_TTL_INDEX, retention, runCommand);
    }

    private static void ttl(MongoCollection<Document> collection, String field, String name, Duration retention,
            Consumer<Document> runCommand) {
        long seconds = retention.toSeconds();
        IndexOptions options = new IndexOptions().name(name).expireAfter(seconds, TimeUnit.SECONDS);
        try {
            collection.createIndex(Indexes.ascending(field), options);
        }
        catch (MongoCommandException e) {
            if (e.getErrorCode() != INDEX_OPTIONS_CONFLICT && e.getErrorCode() != INDEX_KEY_SPECS_CONFLICT) {
                throw e;
            }
            try {
                runCommand.accept(new Document("collMod", collection.getNamespace().getCollectionName()).append("index",
                        new Document("name", name).append("expireAfterSeconds", seconds)));
            }
            catch (RuntimeException collModRefused) {
                // collMod needs more than the usual readWrite role; dropping and recreating the index does not.
                collection.dropIndex(name);
                collection.createIndex(Indexes.ascending(field), options);
            }
        }
    }
}
