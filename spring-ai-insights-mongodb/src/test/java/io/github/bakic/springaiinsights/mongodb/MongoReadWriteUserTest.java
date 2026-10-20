package io.github.bakic.springaiinsights.mongodb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import io.github.bakic.springaiinsights.analytics.ContractTurns;

import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Production-like access: authentication on, and the application user only has {@code readWrite} on its database. */
class MongoReadWriteUserTest {

    private static GenericContainer<?> mongo;

    private static MongoClient appClient;

    @BeforeAll
    static void start() {
        mongo = new GenericContainer<>(MongoTestSupport.IMAGE).withExposedPorts(27017)
            .withEnv("MONGO_INITDB_ROOT_USERNAME", "root")
            .withEnv("MONGO_INITDB_ROOT_PASSWORD", "root")
            .waitingFor(Wait.forLogMessage("(?i).*waiting for connections.*", 2));
        mongo.start();
        String host = mongo.getHost() + ":" + mongo.getMappedPort(27017);
        try (MongoClient root = MongoClients.create("mongodb://root:root@" + host + "/?authSource=admin")) {
            root.getDatabase("app")
                .runCommand(new Document("createUser", "app").append("pwd", "app")
                    .append("roles", List.of(new Document("role", "readWrite").append("db", "app"))));
        }
        appClient = MongoClients.create("mongodb://app:app@" + host + "/app?authSource=app");
    }

    @AfterAll
    static void stop() {
        appClient.close();
        mongo.stop();
    }

    @Test
    void retentionChangeUpdatesTtlIndexWithReadWriteRoleOnly() {
        var template = new MongoTemplate(appClient, "app");
        var longer = new MongoEventStore(template, "ai_insights_", Duration.ofDays(90));
        longer.save(ContractTurns.T1);
        longer.saveFeedback(ContractTurns.F5);

        var shorter = new MongoEventStore(template, "ai_insights_", Duration.ofDays(30));
        shorter.ensureIndexes();

        List<Document> indexes = template.getCollection("ai_insights_turns").listIndexes().into(new ArrayList<>());
        assertThat(indexes).filteredOn(i -> MongoIndexes.TTL_INDEX.equals(i.get("name")))
            .singleElement()
            .satisfies(i -> assertThat(((Number) i.get("expireAfterSeconds")).longValue())
                .isEqualTo(Duration.ofDays(30).toSeconds()));
        assertThat(shorter.findTurn("T1")).isPresent();
        List<Document> feedbackIndexes = template.getCollection("ai_insights_feedback").listIndexes()
            .into(new ArrayList<>());
        assertThat(feedbackIndexes).filteredOn(i -> "ttl_createdAt".equals(i.get("name")))
            .singleElement()
            .satisfies(i -> assertThat(((Number) i.get("expireAfterSeconds")).longValue())
                .isEqualTo(Duration.ofDays(30).toSeconds()));
    }
}
