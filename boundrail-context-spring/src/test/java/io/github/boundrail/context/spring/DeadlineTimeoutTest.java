package io.github.boundrail.context.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.Deadline;
import io.github.boundrail.context.DeadlineExceededException;
import io.github.boundrail.context.spring.support.RecordingServer;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

/** The interceptor stops a call whose remaining time runs out before the response headers arrive, on the JDK {@code HttpClient} factory (DL-07). */
class DeadlineTimeoutTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class, HttpClientAutoConfiguration.class,
                    ImperativeHttpClientAutoConfiguration.class, RestClientAutoConfiguration.class))
            .withPropertyValues("spring.http.clients.imperative.factory=jdk");

    /**
     * What a call did: what it threw, or {@code null}; how long it took; whether the calling thread was left interrupted; and whether an
     * interrupt arrived in the 500ms after the call, while the context was still open.
     */
    private record Outcome(Throwable thrown, Duration elapsed, boolean interrupted, boolean interruptedLater) {
    }

    /**
     * Calls the server through a client from the auto-configured builder, inside a deadline of {@code budget} if any, reading the body as a
     * string, on the test thread.
     */
    private Outcome call(Supplier<RecordingServer> servers, Consumer<RestClient.Builder> declaration, Duration budget, String... properties) {
        List<Outcome> outcome = new ArrayList<>();
        try (RecordingServer server = servers.get()) {
            runner.withPropertyValues(properties).run(context -> {
                RestClient client = context.getBean(RestClient.Builder.class).baseUrl(server.uri()).apply(declaration).build();
                Runnable request = () -> client.get().uri("/").retrieve().body(String.class);
                long start = System.nanoTime();
                Throwable thrown = catchThrowable(() -> {
                    if (budget == null) {
                        request.run();
                    } else {
                        Context.with(BuiltInKeys.DEADLINE, Deadline.after(budget)).run(request);
                    }
                });
                Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
                boolean interrupted = Thread.interrupted();
                boolean interruptedLater = false;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    interruptedLater = true;
                }
                outcome.add(new Outcome(thrown, elapsed, interrupted, interruptedLater || Thread.interrupted()));
            });
        }
        return outcome.getFirst();
    }

    private static Supplier<RecordingServer> delayed(long millis) {
        return () -> RecordingServer.startDelayed("ok", Duration.ofMillis(millis));
    }

    /** Asserts a {@code TIMEOUT} within the 300ms budget plus 200ms, caused by the JDK client's exception for the interrupt. */
    private static void assertTimeout(Outcome outcome, String description) {
        assertThat(outcome.thrown()).as(description).isInstanceOf(DeadlineExceededException.class);
        assertThat(((DeadlineExceededException) outcome.thrown()).stage()).as(description).isEqualTo(DeadlineExceededException.Stage.TIMEOUT);
        assertThat(outcome.thrown().getCause()).as("%s: the client's exception for the interrupt", description)
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(HttpTimeoutException.class)
                .hasMessageContaining("interrupted");
        assertThat(outcome.elapsed()).as("%s: within the remaining time plus 200ms", description).isLessThanOrEqualTo(Duration.ofMillis(500));
        assertThat(outcome.interrupted()).as("%s: the calling thread is not left interrupted", description).isFalse();
        assertThat(outcome.interruptedLater()).as("%s: no interrupt arrives later", description).isFalse();
    }

    /** The class of the exception chain of a call, from the outermost to the root, for comparing with the same call without a deadline. */
    private static List<Class<?>> chain(Throwable thrown) {
        List<Class<?>> classes = new ArrayList<>();
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            classes.add(cause.getClass());
        }
        return classes;
    }

    static Stream<Arguments> cappedCalls() {
        return Stream.of(
                Arguments.of(Named.of("DL-07 a call whose remaining time is shorter than the configured timeout fails with DeadlineExceededException at"
                        + " TIMEOUT within the remaining time plus 200ms, with the client's interrupt exception as the cause", "remaining")),
                Arguments.of(Named.of("DL-07 a client's own shorter timeout propagates unchanged", "configured")),
                Arguments.of(Named.of("DL-07 a timeout while reading the body propagates as the client's own exception", "body")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cappedCalls")
    @DisplayName("DL-07 the effective timeout is the smaller of the configured timeout and the remaining time, until the response headers arrive")
    void theEffectiveTimeoutIsTheSmallerOfTheConfiguredTimeoutAndTheRemainingTime(String scenario) {
        switch (scenario) {
            case "remaining" -> {
                assertTimeout(call(delayed(3000), BoundrailClients.internal("orders"), Duration.ofMillis(300),
                        "spring.http.clients.read-timeout=10s"), "internal");
                assertTimeout(call(delayed(3000), BoundrailClients.external(), Duration.ofMillis(300),
                        "spring.http.clients.read-timeout=10s"), "external");
            }
            case "configured" -> {
                Outcome withDeadline = call(delayed(3000), BoundrailClients.internal("orders"), Duration.ofSeconds(5),
                        "spring.http.clients.read-timeout=200ms");
                Outcome withoutDeadline = call(delayed(3000), BoundrailClients.internal("orders"), null,
                        "spring.http.clients.read-timeout=200ms");

                assertThat(chain(withDeadline.thrown())).as("as without a deadline").isEqualTo(chain(withoutDeadline.thrown()))
                        .contains(HttpTimeoutException.class)
                        .doesNotContain(DeadlineExceededException.class);
                assertThat(withDeadline.interrupted()).isFalse();
            }
            case "body" -> {
                Supplier<RecordingServer> slowBody = () -> RecordingServer.startWithSlowBody("ok", Duration.ofSeconds(3));
                Outcome withDeadline = call(slowBody, BoundrailClients.internal("orders"), Duration.ofMillis(300),
                        "spring.http.clients.read-timeout=1s");
                Outcome withoutDeadline = call(slowBody, BoundrailClients.internal("orders"), null, "spring.http.clients.read-timeout=1s");

                assertThat(withDeadline.thrown()).as("reading the body fails").isNotNull();
                assertThat(chain(withDeadline.thrown())).as("the client's own exception, as without a deadline")
                        .isEqualTo(chain(withoutDeadline.thrown()))
                        .doesNotContain(DeadlineExceededException.class);
                assertThat(withDeadline.interrupted()).isFalse();
            }
            default -> throw new IllegalArgumentException(scenario);
        }
    }

    @Test
    @DisplayName("DL-07 with no configured timeout the remaining time is the timeout")
    void withNoConfiguredTimeoutTheRemainingTimeIsTheTimeout() {
        assertTimeout(call(delayed(3000), BoundrailClients.internal("orders"), Duration.ofMillis(300)), "no read-timeout");
    }

    @Test
    @DisplayName("DL-07 a call that completes within the remaining time is not affected")
    void aCallThatCompletesWithinTheRemainingTimeIsNotAffected() {
        Outcome outcome = call(delayed(20), BoundrailClients.internal("orders"), Duration.ofMillis(300));

        assertThat(outcome.thrown()).as("the call returns normally").isNull();
        assertThat(outcome.interrupted()).as("not interrupted afterwards").isFalse();
        assertThat(outcome.interruptedLater()).as("no interrupt arrives in the next 500ms").isFalse();
    }
}
