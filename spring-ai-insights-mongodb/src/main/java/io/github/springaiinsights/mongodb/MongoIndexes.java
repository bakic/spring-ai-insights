package io.github.springaiinsights.mongodb;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;

/** Creates the indexes of the turns collection; updates the TTL index in place when the retention changes. */
final class MongoIndexes {

    static final String TTL_INDEX = "ttl_startedAt";

    private static final int INDEX_OPTIONS_CONFLICT = 85;

    private static final int INDEX_KEY_SPECS_CONFLICT = 86;

    private MongoIndexes() {
    }

    static void ensure(MongoCollection<Document> turns, Duration retention, Consumer<Document> runCommand) {
        turns.createIndex(Indexes.compoundIndex(Indexes.ascending("application"), Indexes.descending("startedAt")),
                new IndexOptions().name("application_startedAt"));
        turns.createIndex(
                Indexes.compoundIndex(Indexes.ascending("application", "conversationId"),
                        Indexes.ascending("startedAt")),
                new IndexOptions().name("application_conversationId_startedAt"));
        turns.createIndex(
                Indexes.compoundIndex(Indexes.ascending("application", "tenantId"), Indexes.descending("startedAt")),
                new IndexOptions().name("application_tenantId_startedAt"));
        long seconds = retention.toSeconds();
        try {
            turns.createIndex(Indexes.ascending("startedAt"),
                    new IndexOptions().name(TTL_INDEX).expireAfter(seconds, TimeUnit.SECONDS));
        }
        catch (MongoCommandException e) {
            if (e.getErrorCode() != INDEX_OPTIONS_CONFLICT && e.getErrorCode() != INDEX_KEY_SPECS_CONFLICT) {
                throw e;
            }
            runCommand.accept(new Document("collMod", turns.getNamespace().getCollectionName()).append("index",
                    new Document("name", TTL_INDEX).append("expireAfterSeconds", seconds)));
        }
    }
}
