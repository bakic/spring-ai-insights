package io.github.bakic.springaiinsights.mongodb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.github.bakic.springaiinsights.report.ReportLock;
import io.github.bakic.springaiinsights.report.ReportLockContract;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class MongoReportLockTest extends ReportLockContract {

    private final MongoTemplate template = MongoTestSupport.freshTemplate();

    private final MongoEventStore store = new MongoEventStore(template, "ai_insights_", Duration.ofDays(90));

    @Override
    protected ReportLock lock() {
        return store;
    }

    @Test
    void claimHasTtlIndex() {
        assertThat(store.locksCollectionName()).isEqualTo("ai_insights_locks");
        store.claim("k", expiry());
        List<Document> indexes = template.getCollection("ai_insights_locks").listIndexes().into(new ArrayList<>());
        assertThat(indexes).anySatisfy(index -> {
            assertThat(index.getString("name")).isEqualTo("ttl_expiresAt");
            assertThat(((Number) index.get("expireAfterSeconds")).longValue()).isZero();
        });
    }
}
