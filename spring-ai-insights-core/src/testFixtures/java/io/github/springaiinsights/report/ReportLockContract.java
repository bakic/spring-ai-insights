package io.github.springaiinsights.report;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Behaviour every {@link ReportLock} must have. */
public abstract class ReportLockContract {

    protected abstract ReportLock lock();

    protected Instant expiry() {
        return Instant.now().plus(Duration.ofDays(35));
    }

    @Test
    void firstClaimWins() {
        ReportLock lock = lock();
        assertThat(lock.claim("k", expiry())).isTrue();
        assertThat(lock.claim("k", expiry())).isFalse();
    }

    @Test
    void keysAreIndependent() {
        ReportLock lock = lock();
        assertThat(lock.claim("a", expiry())).isTrue();
        assertThat(lock.claim("b", expiry())).isTrue();
    }

    @Test
    void concurrentClaimsHaveOneWinner() throws Exception {
        ReportLock lock = lock();
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return lock.claim("race", expiry());
                }));
            }
            start.countDown();
            long winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        }
        finally {
            executor.shutdownNow();
        }
    }
}
