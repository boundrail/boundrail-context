package io.github.boundrail.context.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.boundrail.context.DeadlineExceededException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

/** The timer the interceptor arms around {@code execute}, and its handshake with the calling thread (DL-07, RT-09). */
class DeadlineTimerTest {

    private final DeadlineTimer timer = new DeadlineTimer(stage -> { });

    @AfterEach
    void closeTimer() {
        timer.close();
        Thread.interrupted();
    }

    /** Waits, without sleeping, until the current thread is interrupted, leaving the interrupt set; fails after 5s. */
    private static void awaitInterrupt() {
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Thread.currentThread().isInterrupted()) {
            if (System.nanoTime() > giveUp) {
                throw new AssertionError("the timer did not interrupt the thread");
            }
            LockSupport.parkNanos(100_000);
        }
    }

    /** Waits, without sleeping, for the given time; an interrupt does not end the wait. */
    private static void spin(Duration duration) {
        long until = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < until) {
            Thread.onSpinWait();
        }
    }

    private ClientHttpResponse execute(Duration remaining, ClientHttpRequestExecution execution) throws IOException {
        return timer.execute(remaining, new MockClientHttpRequest(), new byte[0], execution);
    }

    /** {@return whether an interrupt is set now, or arrives in the next 500ms} */
    private static boolean interruptedNowOrLater() {
        if (Thread.interrupted()) {
            return true;
        }
        try {
            Thread.sleep(500);
            return Thread.interrupted();
        } catch (InterruptedException e) {
            return true;
        }
    }

    @Test
    @DisplayName("DL-07 a response that arrives as the time runs out is kept and no interrupt leaks")
    void aResponseThatArrivesAsTheTimeRunsOutIsKeptAndNoInterruptLeaks() throws IOException {
        MockClientHttpResponse atTheDeadline = new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        MockClientHttpResponse justBefore = new MockClientHttpResponse(new byte[0], HttpStatus.OK);

        ClientHttpResponse kept = execute(Duration.ofMillis(50), (request, body) -> {
            awaitInterrupt();
            return atTheDeadline;
        });
        boolean leakedAtTheDeadline = interruptedNowOrLater();
        ClientHttpResponse returned = execute(Duration.ofMillis(50), (request, body) -> {
            spin(Duration.ofMillis(49));
            return justBefore;
        });
        boolean leakedJustBefore = interruptedNowOrLater();

        assertThat(kept).as("the response that arrived as the timer fired").isSameAs(atTheDeadline);
        assertThat(leakedAtTheDeadline).as("the timer's interrupt is cleared").isFalse();
        assertThat(returned).isSameAs(justBefore);
        assertThat(leakedJustBefore).as("no interrupt arrives after a call that returned just before the timer was due").isFalse();
    }

    @Test
    @DisplayName("DL-07 an IOException or a RuntimeException after the timer fired becomes TIMEOUT, with it as the cause")
    void anIoExceptionOrARuntimeExceptionAfterTheTimerFiredBecomesTimeout() {
        for (Exception failure : List.of(new IOException("Request was interrupted"), new CancellationException())) {
            Throwable afterFiring = catchThrowable(() -> execute(Duration.ofMillis(50), (request, body) -> {
                awaitInterrupt();
                throw sneaky(failure);
            }));
            boolean leaked = interruptedNowOrLater();
            Throwable beforeFiring = catchThrowable(() -> execute(Duration.ofSeconds(5), (request, body) -> {
                throw sneaky(failure);
            }));

            assertThat(afterFiring).as("%s after the timer fired", failure).isInstanceOf(DeadlineExceededException.class).hasCause(failure);
            assertThat(((DeadlineExceededException) afterFiring).stage()).isEqualTo(DeadlineExceededException.Stage.TIMEOUT);
            assertThat(leaked).as("the timer's interrupt is cleared").isFalse();
            assertThat(beforeFiring).as("%s before the timer fired propagates unchanged", failure).isSameAs(failure);
        }
    }

    @Test
    @DisplayName("DL-07 a disarmed timer leaves the scheduler's queue at once")
    void aDisarmedTimerLeavesTheSchedulersQueueAtOnce() throws IOException {
        MockClientHttpResponse response = new MockClientHttpResponse(new byte[0], HttpStatus.OK);

        execute(Duration.ofSeconds(30), (request, body) -> response);

        assertThat(timer.queuedTimers()).as("no cancelled timer waits out its 30s, holding the caller's thread").isZero();
    }

    @Test
    @DisplayName("DL-07 an interrupt already set before the call is never cleared, and the client's exception is rethrown unchanged")
    void anInterruptAlreadySetBeforeTheCallIsNeverCleared() {
        IOException clients = new IOException("Request was interrupted");
        Thread.currentThread().interrupt();

        Throwable thrown = catchThrowable(() -> execute(Duration.ZERO, (request, body) -> {
            spin(Duration.ofMillis(20));
            throw clients;
        }));
        boolean stillInterrupted = Thread.interrupted();

        assertThat(thrown).as("rethrown unchanged, not TIMEOUT").isSameAs(clients);
        assertThat(stillInterrupted).as("the application's interrupt is kept").isTrue();
    }

    @Test
    @DisplayName("DL-07 a call made after the timer has shut down runs without the cap")
    void aCallMadeAfterTheTimerHasShutDownRunsWithoutTheCap() throws IOException {
        MockClientHttpResponse response = new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        timer.close();

        ClientHttpResponse returned = execute(Duration.ofSeconds(30), (request, body) -> response);

        assertThat(returned).as("the execution's response, not RejectedExecutionException").isSameAs(response);
    }

    @Test
    @DisplayName("DL-07 the scheduler is a bean of the context and stops when it closes")
    void theSchedulerIsABeanOfTheContextAndStopsWhenItCloses() {
        AtomicReference<DeadlineTimer> bean = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class, RestClientAutoConfiguration.class))
                .run(context -> {
                    bean.set(context.getBean(DeadlineTimer.class));
                    assertThat(bean.get().isShutdown()).as("running while the context is open").isFalse();
                });

        assertThat(bean.get().isShutdown()).as("shut down when the context closes").isTrue();
    }

    /** Rethrows a checked exception from a lambda that does not declare it. */
    @SuppressWarnings("unchecked")
    private static <E extends Exception> E sneaky(Exception e) throws E {
        throw (E) e;
    }
}
