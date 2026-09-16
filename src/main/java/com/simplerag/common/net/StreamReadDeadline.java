package com.simplerag.common.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Closes a streamed HTTP body when its read deadline expires.
 *
 * <p>{@code HttpRequest.timeout} completes once response headers arrive for an
 * {@code ofInputStream} body. Closing the body from a daemon watchdog is what releases a thread that
 * is already blocked in {@code read()} or {@code readLine()}.
 */
public final class StreamReadDeadline implements AutoCloseable {
    private static final AtomicInteger THREADS = new AtomicInteger();
    private static final ThreadFactory DAEMON_FACTORY = runnable -> {
        Thread thread = new Thread(runnable, "simplerag-stream-deadline-" + THREADS.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    };
    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(DAEMON_FACTORY);

    private final InputStream stream;
    private final Duration timeout;
    private ScheduledFuture<?> future;
    private long generation;
    private boolean expired;
    private boolean closed;

    public StreamReadDeadline(InputStream stream, Duration timeout) {
        this.stream = Objects.requireNonNull(stream, "stream");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        arm();
    }

    /** Restarts the deadline after bytes or a complete protocol line arrived. */
    public synchronized void activity() {
        if (!closed && !expired) arm();
    }

    public synchronized void throwIfExpired(String message) throws HttpTimeoutException {
        if (expired) throw timeout(message, null);
    }

    /** Converts the close-induced read failure into an explicit timeout. */
    public synchronized IOException translate(IOException failure, String message) {
        return expired ? timeout(message, failure) : failure;
    }

    private synchronized void arm() {
        generation++;
        long expected = generation;
        if (future != null) future.cancel(false);
        future = WATCHDOG.schedule(() -> expire(expected), timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void expire(long expected) {
        synchronized (this) {
            if (closed || expired || generation != expected) return;
            expired = true;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // The timeout itself is reported by translate()/throwIfExpired(); a close failure adds no
            // actionable information and must not kill the shared watchdog thread.
        }
    }

    private static HttpTimeoutException timeout(String message, IOException cause) {
        HttpTimeoutException timeout = new HttpTimeoutException(message);
        if (cause != null) timeout.initCause(cause);
        return timeout;
    }

    @Override
    public synchronized void close() {
        closed = true;
        generation++;
        if (future != null) future.cancel(false);
    }
}
