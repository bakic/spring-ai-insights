package io.github.bakic.springaiinsights.mongodb;

import java.util.UUID;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.testcontainers.mongodb.MongoDBContainer;

import org.springframework.data.mongodb.core.MongoTemplate;

/** One MongoDB 5.0 container (the supported floor) shared by the module's tests; each test gets a fresh database. */
final class MongoTestSupport {

    static final String IMAGE = System.getProperty("ai-insights.test.mongo-image", "mongo:5.0");

    private static MongoDBContainer container;

    private static MongoClient client;

    private MongoTestSupport() {
    }

    static synchronized MongoTemplate freshTemplate() {
        if (container == null) {
            container = new MongoDBContainer(IMAGE);
            container.start();
            client = MongoClients.create(container.getConnectionString());
        }
        return new MongoTemplate(client, "t" + UUID.randomUUID().toString().replace("-", ""));
    }
}
