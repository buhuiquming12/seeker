package com.simplerag.common.net;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;

class StreamReadDeadlineTest {
    @Test
    void closingTheBodyReleasesABlockedReadAndReportsTimeout() throws Exception {
        BlockingInputStream input = new BlockingInputStream();
        StreamReadDeadline deadline = new StreamReadDeadline(input, Duration.ofMillis(50));

        try (deadline) {
            input.read();
            assertThrows(HttpTimeoutException.class,
                    () -> deadline.throwIfExpired("stream idle"));
        }
    }

    private static final class BlockingInputStream extends InputStream {
        private boolean closed;

        @Override
        public synchronized int read() throws IOException {
            while (!closed) {
                try {
                    wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            return -1;
        }

        @Override
        public synchronized void close() {
            closed = true;
            notifyAll();
        }
    }
}
