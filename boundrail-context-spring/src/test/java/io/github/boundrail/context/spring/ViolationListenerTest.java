package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Boundary.ATTACHED;
import static io.github.boundrail.context.Boundary.EXTERNAL_CALL;
import static io.github.boundrail.context.Boundary.INTERNAL_CALL;
import static io.github.boundrail.context.Violation.Kind.FORBIDDEN_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.ContextViolationException;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.spring.support.RecordingLog;
import io.github.boundrail.context.spring.support.RecordingLog.Entry;
import io.github.boundrail.context.spring.support.RecordingLog.Level;
import io.github.boundrail.context.spring.support.RecordingServer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.annotation.Order;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class ViolationListenerTest {

    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);

    private static final Violation VIOLATION = new Violation(FORBIDDEN_HEADER, "tenant", EXTERNAL_CALL, Optional.empty(), "worker-1",
            Optional.empty(), Optional.empty());

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<Violation> counted = new CopyOnWriteArrayList<>();
    private final RecordingLog log = new RecordingLog();

    /** A listener that runs the given action; subclasses carry an {@code @Order}. */
    abstract static class ActionListener implements ViolationListener {

        private final Consumer<Violation> action;

        ActionListener(Consumer<Violation> action) {
            this.action = action;
        }

        @Override
        public void onViolation(Violation violation) {
            action.accept(violation);
        }
    }

    @Order(1)
    static final class FirstListener extends ActionListener {
        FirstListener(Consumer<Violation> action) {
            super(action);
        }
    }

    @Order(2)
    static final class SecondListener extends ActionListener {
        SecondListener(Consumer<Violation> action) {
            super(action);
        }
    }

    /** A listener that records what it saw, and how many violations had been counted at that moment. */
    private Consumer<Violation> recording(String name) {
        return violation -> events.add(name + " saw " + violation.kind() + " after " + counted.size() + " counted");
    }

    /** A reporter wired like the auto-configured one, with the context's listener beans, a recording counter and a recording log. */
    private ViolationReporter reporter(AssertableApplicationContext context) {
        return new ViolationReporter(new EffectivePolicy(BuiltInKeys.defaultPolicy()), Duration.ofMinutes(1), counted::add,
                context.getBeanProvider(ViolationListener.class), log, Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC));
    }

    private List<Entry> warnings() {
        return log.entries().stream().filter(entry -> entry.level() == Level.WARN).toList();
    }

    @Test
    @DisplayName("DIAG-11 every ViolationListener bean receives each violation in bean order, after it is counted, also when its log entry is rate-limited")
    void everyListenerReceivesEachViolationInBeanOrderAfterItIsCounted() {
        // Registered in reverse order, so only bean order (@Order) can put "first" before "second".
        new ApplicationContextRunner()
                .withBean(SecondListener.class, () -> new SecondListener(recording("second")))
                .withBean(FirstListener.class, () -> new FirstListener(recording("first")))
                .run(context -> {
                    ViolationReporter reporter = reporter(context);

                    reporter.report(VIOLATION);
                    reporter.report(VIOLATION);
                });

        assertThat(events).containsExactly(
                "first saw FORBIDDEN_HEADER after 1 counted", "second saw FORBIDDEN_HEADER after 1 counted",
                "first saw FORBIDDEN_HEADER after 2 counted", "second saw FORBIDDEN_HEADER after 2 counted");
        assertThat(warnings()).as("the second entry is rate-limited").hasSize(1);
    }

    @Test
    @DisplayName("DIAG-11 a throwing listener is logged at WARN with the exception, the remaining listeners still run, and the outcome is unchanged")
    void aThrowingListenerIsLoggedAndTheRemainingListenersStillRun(CapturedOutput output) {
        IllegalStateException listenerFailure = new IllegalStateException("listener failed");
        Consumer<Violation> throwing = violation -> {
            throw listenerFailure;
        };
        new ApplicationContextRunner()
                .withBean(FirstListener.class, () -> new FirstListener(throwing))
                .withBean(SecondListener.class, () -> new SecondListener(recording("second")))
                .run(context -> reporter(context).report(VIOLATION));

        assertThat(events).containsExactly("second saw FORBIDDEN_HEADER after 1 counted");
        assertThat(warnings()).filteredOn(entry -> entry.throwable() != null).singleElement().satisfies(entry -> {
            assertThat(entry.throwable()).isSameAs(listenerFailure);
            assertThat(entry.message()).contains(FirstListener.class.getName());
        });

        ContextPolicy requiresTenant = ContextPolicy.builder()
                .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("Tenant-Id").required().propagateTo(ATTACHED, INTERNAL_CALL))
                .build();
        events.clear();
        Throwable[] thrown = new Throwable[1];
        try (RecordingServer server = RecordingServer.start("ok")) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class, RestClientAutoConfiguration.class))
                    .withPropertyValues("boundrail.context.violations.mode=fail")
                    .withBean(ContextPolicy.class, () -> requiresTenant)
                    .withBean(FirstListener.class, () -> new FirstListener(throwing))
                    .withBean(SecondListener.class, () -> new SecondListener(recording("second")))
                    .run(context -> {
                        RestClient client = context.getBean(RestClient.Builder.class).baseUrl(server.uri())
                                .apply(BoundrailClients.internal("orders")).build();
                        thrown[0] = catchThrowable(() -> Context.without(TENANT).run(() -> client.get().uri("/").retrieve().toBodilessEntity()));
                    });
        }

        assertThat(thrown[0]).as("in fail mode the interceptor's exception, not the listener's").isInstanceOf(ContextViolationException.class);
        assertThat(events).as("the auto-configured reporter calls the listener beans").singleElement().asString()
                .startsWith("second saw MISSING_REQUIRED");
        assertThat(output.getOut()).contains("Violation listener " + FirstListener.class.getName() + " failed");
    }

    @Test
    @DisplayName("RT-09 violation log rate limiting belongs to one application context")
    void violationLogRateLimitingBelongsToOneApplicationContext(CapturedOutput output) {
        Violation unique = new Violation(FORBIDDEN_HEADER, "rt09.rate-limit", EXTERNAL_CALL, Optional.empty(), "worker-1",
                Optional.empty(), Optional.empty());
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class));

        runner.run(context -> context.getBean(ViolationReporter.class).report(unique));
        runner.run(context -> context.getBean(ViolationReporter.class).report(unique));

        assertThat(output.getOut().lines().filter(line -> line.contains("key rt09.rate-limit"))).as("one entry per context").hasSize(2);
    }
}
