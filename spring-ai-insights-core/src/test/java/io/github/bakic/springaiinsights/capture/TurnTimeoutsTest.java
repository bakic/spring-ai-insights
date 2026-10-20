package io.github.bakic.springaiinsights.capture;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurnTimeoutsTest {

    @Test
    void runsTaskAfterTimeoutOnDaemonThread() throws InterruptedException {
        try (var timeouts = new TurnTimeouts(Duration.ofMillis(50))) {
            var ran = new CountDownLatch(1);
            var daemon = new AtomicBoolean();
            long start = System.nanoTime();
            timeouts.schedule(() -> {
                daemon.set(Thread.currentThread().isDaemon()
                        && Thread.currentThread().getName().equals("ai-insights-timeouts"));
                ran.countDown();
            });
            assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(Duration.ofMillis(50).toNanos());
            assertThat(daemon).isTrue();
        }
    }

    @Test
    void cancelledTaskDoesNotRun() throws InterruptedException {
        try (var timeouts = new TurnTimeouts(Duration.ofMillis(50))) {
            var ran = new AtomicBoolean();
            timeouts.schedule(() -> ran.set(true)).cancel(false);
            Thread.sleep(150);
            assertThat(ran).isFalse();
        }
    }

    @Test
    void scheduleAfterCloseDoesNothing() throws InterruptedException {
        var timeouts = new TurnTimeouts(Duration.ofMillis(10));
        timeouts.close();
        var ran = new AtomicBoolean();
        assertThat(timeouts.schedule(() -> ran.set(true)).isDone()).isTrue();
        Thread.sleep(50);
        assertThat(ran).isFalse();
    }

    @Test
    void rejectsNonPositiveTimeout() {
        assertThatThrownBy(() -> new TurnTimeouts(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
