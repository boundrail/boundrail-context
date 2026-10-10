package io.github.boundrail.context.spring;

import io.github.boundrail.context.DeadlineExceededException;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Stops an outbound call whose remaining time runs out before the response headers arrive, by interrupting the calling thread (DL-07). One
 * per application context, on one daemon thread, shut down when the context closes (RT-09).
 */
final class DeadlineTimer implements AutoCloseable {

    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "boundrail-deadline-timer");
        thread.setDaemon(true);
        return thread;
    });

    DeadlineTimer() {
        // A disarmed timer leaves the queue at once, instead of waiting out its delay while holding the caller's thread.
        scheduler.setRemoveOnCancelPolicy(true);
    }

    /**
     * Executes the request with a one-shot timer armed for {@code remaining}; if the timer fires first, it interrupts the calling thread. Once
     * {@code execute} returns or throws, the timer is disarmed and an interrupt it delivered is cleared, so none remains or arrives later. A
     * response is returned even if the timer fired as it arrived. An {@code IOException} or {@code RuntimeException} thrown after the timer
     * fired becomes {@code DeadlineExceededException} with stage {@code TIMEOUT} and that cause; before it fired, it propagates unchanged.
     * If the calling thread is already interrupted, executes the request without a timer.
     */
    ClientHttpResponse execute(Duration remaining, HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            // An interrupt set before the call is the application's: never cleared, and the client's exception propagates (DL-07).
            return execution.execute(request, body);
        }
        Armed armed = new Armed(Thread.currentThread());
        armed.timer = scheduler.schedule(armed::fire, remaining.toNanos(), TimeUnit.NANOSECONDS);
        try {
            return execution.execute(request, body);
        } catch (IOException | RuntimeException e) {
            if (armed.disarm()) {
                throw new DeadlineExceededException(DeadlineExceededException.Stage.TIMEOUT, e);
            }
            throw e;
        } finally {
            armed.disarm();
        }
    }

    /** {@return how many timers are queued} */
    int queuedTimers() {
        return scheduler.getQueue().size();
    }

    /** {@return whether the timer's thread has been shut down} */
    boolean isShutdown() {
        return scheduler.isShutdown();
    }

    /** Shuts the timer's thread down; armed timers no longer fire. */
    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    /**
     * The handshake between the timer and the calling thread: both sides take the lock, so after {@link #disarm} the timer can no longer
     * interrupt, and if it fired, its interrupt has already been delivered and is cleared.
     */
    private static final class Armed {

        private final Thread caller;
        private final ReentrantLock lock = new ReentrantLock();
        private ScheduledFuture<?> timer;
        private boolean done;
        private boolean fired;
        /** Read and written by the calling thread only. */
        private boolean disarmed;

        Armed(Thread caller) {
            this.caller = caller;
        }

        void fire() {
            lock.lock();
            try {
                if (!done) {
                    fired = true;
                    caller.interrupt();
                }
            } finally {
                lock.unlock();
            }
        }

        /** {@return whether the timer fired}; on the first call, disarms it and clears its interrupt if it fired */
        boolean disarm() {
            if (disarmed) {
                return fired;
            }
            disarmed = true;
            timer.cancel(false);
            lock.lock();
            try {
                done = true;
            } finally {
                lock.unlock();
            }
            if (fired) {
                Thread.interrupted();
            }
            return fired;
        }
    }
}
