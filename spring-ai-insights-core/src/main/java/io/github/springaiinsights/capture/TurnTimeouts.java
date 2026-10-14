package io.github.springaiinsights.capture;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Runs a task once a turn has waited too long for its model calls to report. One daemon thread, created on first
 * use.
 */
public final class TurnTimeouts implements AutoCloseable {

    private static final Future<?> NONE = CompletableFuture.completedFuture(null);

    private final Duration stallTimeout;

    private ScheduledThreadPoolExecutor executor;

    private boolean closed;

    public TurnTimeouts(Duration stallTimeout) {
        if (stallTimeout.isNegative() || stallTimeout.isZero()) {
            throw new IllegalArgumentException("stallTimeout must be positive: " + stallTimeout);
        }
        this.stallTimeout = stallTimeout;
    }

    /** Runs {@code task} after the stall timeout; cancel the returned future when it is no longer needed. */
    public Future<?> schedule(Runnable task) {
        ScheduledThreadPoolExecutor scheduler = executor();
        if (scheduler == null) {
            return NONE;
        }
        try {
            return scheduler.schedule(task, stallTimeout.toNanos(), TimeUnit.NANOSECONDS);
        }
        catch (RejectedExecutionException e) {
            return NONE;
        }
    }

    private synchronized ScheduledThreadPoolExecutor executor() {
        if (closed) {
            return null;
        }
        if (executor == null) {
            executor = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "ai-insights-timeouts");
                thread.setDaemon(true);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
        }
        return executor;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
