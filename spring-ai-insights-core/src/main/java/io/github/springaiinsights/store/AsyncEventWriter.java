package io.github.springaiinsights.store;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.github.springaiinsights.metrics.InsightsMetrics;
import io.github.springaiinsights.model.ConversationTurn;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Saves turns off the chat path. {@link #submit} never blocks: when the bounded queue is full the turn is dropped
 * and counted. Store failures are counted and logged at most once per minute; the writer thread keeps running.
 */
public final class AsyncEventWriter implements AutoCloseable {

    private static final Log log = LogFactory.getLog(AsyncEventWriter.class);

    private static final long WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    private static final long DRAIN_TIMEOUT_MS = 5_000;

    private final InsightsEventStore store;

    private final InsightsMetrics metrics;

    private final BlockingQueue<ConversationTurn> queue;

    private final Thread worker;

    private final AtomicLong lastWarnNanos = new AtomicLong(System.nanoTime() - WARN_INTERVAL_NANOS);

    private volatile boolean running = true;

    public AsyncEventWriter(InsightsEventStore store, int capacity, InsightsMetrics metrics) {
        this.store = store;
        this.metrics = metrics;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.worker = new Thread(this::drain, "ai-insights-writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public boolean submit(ConversationTurn turn) {
        if (!running || !queue.offer(turn)) {
            metrics.eventDropped();
            return false;
        }
        return true;
    }

    private void drain() {
        while (running || !queue.isEmpty()) {
            ConversationTurn turn;
            try {
                turn = queue.poll(100, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (turn != null) {
                save(turn);
            }
        }
    }

    private void save(ConversationTurn turn) {
        try {
            store.save(turn);
        }
        catch (RuntimeException e) {
            metrics.storeError();
            long now = System.nanoTime();
            long last = lastWarnNanos.get();
            if (now - last >= WARN_INTERVAL_NANOS && lastWarnNanos.compareAndSet(last, now)) {
                log.warn("ai-insights: failed to save turn " + turn.turnId() + "; insights data is being lost", e);
            }
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            worker.join(DRAIN_TIMEOUT_MS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            worker.interrupt();
            int lost = queue.size();
            if (lost > 0) {
                log.warn("ai-insights: shutdown timed out; " + lost + " turns were not saved");
            }
        }
    }
}
