package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Boundary.EXTERNAL_CALL;
import static io.github.boundrail.context.Boundary.INTERNAL_CALL;
import static io.github.boundrail.context.Violation.Kind.FORBIDDEN_HEADER;
import static io.github.boundrail.context.Violation.Kind.MISSING_REQUIRED;
import static io.github.boundrail.context.Violation.Kind.UNTRUSTED_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.Destination;
import io.github.boundrail.context.DestinationClassifier;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.spring.support.RecordingLog;
import io.github.boundrail.context.spring.support.RecordingLog.Entry;
import io.github.boundrail.context.spring.support.RecordingLog.Level;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ViolationReporterTest {

    private static final String COUNTER = "boundrail.context.violations";
    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);
    private static final ContextKey<String> REGION = ContextKey.of("region", String.class);
    private static final ContextKey<String> PRIORITY = ContextKey.of("priority", String.class);

    private final EffectivePolicy policy = new EffectivePolicy(BuiltInKeys.defaultPolicy().merge(ContextPolicy.builder()
            .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("Tenant-Id").propagateTo(INTERNAL_CALL))
            .key(REGION, rules -> rules.codec(Codecs.string()).wireName("Region").propagateTo(INTERNAL_CALL))
            .key(PRIORITY, rules -> rules.loggable())
            .build()));

    private final RecordingLog log = new RecordingLog();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T10:00:00Z"));
    private final ViolationReporter reporter = new ViolationReporter(policy, Duration.ofMinutes(1), violation -> { },
            new DefaultListableBeanFactory().getBeanProvider(ViolationListener.class), thread -> Optional.empty(), log, clock);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class));

    private static Violation violation(Violation.Kind kind, String key, Boundary boundary) {
        return new Violation(kind, key, boundary, Optional.empty(), "worker-1", Optional.empty(), Optional.empty());
    }

    private List<Entry> warnings() {
        return log.entries().stream().filter(entry -> entry.level() == Level.WARN).toList();
    }

    @Test
    @DisplayName("DIAG-03 a violation is logged at WARN")
    void aViolationIsLoggedAtWarn() {
        reporter.report(new Violation(FORBIDDEN_HEADER, "tenant", EXTERNAL_CALL,
                Optional.of(new Destination(Destination.Kind.UNKNOWN, Optional.empty())), "worker-7", Optional.empty(), Optional.empty()));

        assertThat(log.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(Level.WARN);
            assertThat(entry.message()).contains("FORBIDDEN_HEADER", "tenant", "EXTERNAL_CALL", "UNKNOWN", "worker-7");
            assertThat(entry.throwable()).isNull();
        });
    }

    @Test
    @DisplayName("DIAG-09 the log entry identifies the value by key name, wire name and boundary")
    void theLogEntryIdentifiesTheValueByKeyNameWireNameAndBoundary() {
        reporter.report(violation(UNTRUSTED_HEADER, "tenant", INTERNAL_CALL));
        reporter.report(violation(MISSING_REQUIRED, "priority", INTERNAL_CALL));

        assertThat(warnings()).hasSize(2);
        assertThat(warnings().get(0).message()).contains("tenant", "Tenant-Id", "INTERNAL_CALL");
        assertThat(warnings().get(1).message()).contains("priority", "INTERNAL_CALL").doesNotContain("Tenant-Id", "null", "Optional");
    }

    @Test
    @DisplayName("DIAG-03 log entries are rate-limited per kind, key and boundary")
    void logEntriesAreRateLimitedPerKindKeyAndBoundary() {
        reporter.report(violation(FORBIDDEN_HEADER, "tenant", EXTERNAL_CALL));
        clock.advance(Duration.ofSeconds(30));
        reporter.report(violation(FORBIDDEN_HEADER, "tenant", EXTERNAL_CALL));

        assertThat(warnings()).as("a second identical violation inside log-interval").hasSize(1);

        reporter.report(violation(UNTRUSTED_HEADER, "tenant", EXTERNAL_CALL));
        reporter.report(violation(FORBIDDEN_HEADER, "region", EXTERNAL_CALL));
        reporter.report(violation(FORBIDDEN_HEADER, "tenant", INTERNAL_CALL));

        assertThat(warnings()).as("another kind, key or boundary").hasSize(4);
        assertThat(warnings().get(1).message()).contains("UNTRUSTED_HEADER", "tenant", "EXTERNAL_CALL");
        assertThat(warnings().get(2).message()).contains("FORBIDDEN_HEADER", "region", "EXTERNAL_CALL");
        assertThat(warnings().get(3).message()).contains("FORBIDDEN_HEADER", "tenant", "INTERNAL_CALL");

        clock.advance(Duration.ofSeconds(31));
        reporter.report(violation(FORBIDDEN_HEADER, "tenant", EXTERNAL_CALL));

        assertThat(warnings()).as("after log-interval has passed").hasSize(5);
        assertThat(warnings().get(4).message()).contains("FORBIDDEN_HEADER", "tenant", "EXTERNAL_CALL");
    }

    @Test
    @DisplayName("DIAG-03 log-interval defaults to one minute and is configurable")
    void logIntervalDefaultsToOneMinuteAndIsConfigurable() {
        runner.run(context -> assertThat(context.getBean(BoundrailContextProperties.class).violations().logInterval())
                .isEqualTo(Duration.ofMinutes(1)));
        runner.withPropertyValues("boundrail.context.violations.log-interval=5s")
                .run(context -> assertThat(context.getBean(BoundrailContextProperties.class).violations().logInterval())
                        .isEqualTo(Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("DIAG-03 the auto-configured reporter uses the configured log-interval")
    void theAutoConfiguredReporterUsesTheConfiguredLogInterval(CapturedOutput output) {
        runner.withPropertyValues("boundrail.context.violations.log-interval=0s").run(context -> {
            ViolationReporter contextReporter = context.getBean(ViolationReporter.class);
            contextReporter.report(violation(FORBIDDEN_HEADER, "tenant", EXTERNAL_CALL));
            contextReporter.report(violation(FORBIDDEN_HEADER, "tenant", EXTERNAL_CALL));

            assertThat(output.getOut().lines().filter(line -> line.contains("FORBIDDEN_HEADER"))).hasSize(2);
        });
    }

    @Test
    @DisplayName("DIAG-03 a negative log-interval fails startup")
    void aNegativeLogIntervalFailsStartup() {
        runner.withPropertyValues("boundrail.context.violations.log-interval=-1s").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }

    @ParameterizedTest(name = "with an application DestinationClassifier bean: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("DIAG-03 a negative log-interval fails startup with lazy initialization")
    void aNegativeLogIntervalFailsStartupWithLazyInitialization(boolean withApplicationClassifier) {
        ApplicationContextRunner lazy = runner
                .withPropertyValues("spring.main.lazy-initialization=true", "boundrail.context.violations.log-interval=-1s")
                .withInitializer(context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));
        if (withApplicationClassifier) {
            lazy = lazy.withBean(DestinationClassifier.class,
                    () -> request -> new Destination(Destination.Kind.UNKNOWN, Optional.empty()));
        }
        lazy.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    @DisplayName("DIAG-03 every occurrence is counted even when the log entry is rate-limited")
    void everyOccurrenceIsCountedEvenWhenTheLogEntryIsRateLimited(CapturedOutput output) {
        runner.withBean(SimpleMeterRegistry.class).run(context -> {
            ViolationReporter contextReporter = context.getBean(ViolationReporter.class);
            for (int i = 0; i < 3; i++) {
                contextReporter.report(violation(FORBIDDEN_HEADER, "tenant", EXTERNAL_CALL));
            }

            assertThat(context.getBean(MeterRegistry.class).get(COUNTER).counter().count()).isEqualTo(3.0);
            assertThat(output.getOut().lines().filter(line -> line.contains("FORBIDDEN_HEADER"))).hasSize(1);
        });
    }

    @Test
    @DisplayName("DIAG-03 counter tags are kind, key and boundary in lower case")
    void counterTagsAreKindKeyAndBoundaryInLowerCase() {
        runner.withBean(SimpleMeterRegistry.class).run(context -> {
            ViolationReporter contextReporter = context.getBean(ViolationReporter.class);
            contextReporter.report(violation(FORBIDDEN_HEADER, "tenant", EXTERNAL_CALL));
            contextReporter.report(violation(MISSING_REQUIRED, "acme.Tenant", INTERNAL_CALL));

            MeterRegistry registry = context.getBean(MeterRegistry.class);
            assertThat(registry.get(COUNTER).tags("kind", "forbidden_header", "key", "tenant", "boundary", "external_call").counter().count())
                    .isEqualTo(1.0);
            assertThat(registry.get(COUNTER).tags("kind", "missing_required", "key", "acme.tenant", "boundary", "internal_call").counter()
                    .count()).isEqualTo(1.0);
            assertThat(registry.get(COUNTER).counters()).hasSize(2);
        });
    }

    @Test
    @DisplayName("DIAG-03 with a primary MeterRegistry bean among several the counter is in the primary")
    void withAPrimaryMeterRegistryBeanAmongSeveralTheCounterIsInThePrimary() {
        runner.withBean("primaryRegistry", SimpleMeterRegistry.class, SimpleMeterRegistry::new, definition -> definition.setPrimary(true))
                .withBean("otherRegistry", SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    context.getBean(ViolationReporter.class).report(violation(FORBIDDEN_HEADER, "tenant", EXTERNAL_CALL));

                    assertThat(context.getBean("primaryRegistry", MeterRegistry.class).get(COUNTER).counter().count()).isEqualTo(1.0);
                    assertThat(context.getBean("otherRegistry", MeterRegistry.class).find(COUNTER).meter()).isNull();
                });
    }

    @Test
    @DisplayName("DIAG-03 with several MeterRegistry beans and no primary the violation is logged and not counted")
    void withSeveralMeterRegistryBeansAndNoPrimaryTheViolationIsLoggedAndNotCounted(CapturedOutput output) {
        runner.withBean("firstRegistry", SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .withBean("secondRegistry", SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    context.getBean(ViolationReporter.class).report(violation(FORBIDDEN_HEADER, "tenant", EXTERNAL_CALL));

                    assertThat(output.getOut()).contains("FORBIDDEN_HEADER");
                    assertThat(context.getBean("firstRegistry", MeterRegistry.class).find(COUNTER).meter()).isNull();
                    assertThat(context.getBean("secondRegistry", MeterRegistry.class).find(COUNTER).meter()).isNull();
                });
    }

    @Test
    @DisplayName("DIAG-03 without a MeterRegistry bean the violation is logged and the global registry is untouched")
    void withoutAMeterRegistryBeanTheViolationIsLoggedAndTheGlobalRegistryIsUntouched(CapturedOutput output) {
        SimpleMeterRegistry global = new SimpleMeterRegistry();
        Metrics.globalRegistry.add(global);
        try {
            runner.run(context -> {
                context.getBean(ViolationReporter.class).report(violation(FORBIDDEN_HEADER, "tenant", EXTERNAL_CALL));

                assertThat(output.getOut()).contains("FORBIDDEN_HEADER");
                assertThat(global.find(COUNTER).meter()).isNull();
            });
        } finally {
            Metrics.globalRegistry.remove(global);
            global.close();
        }
    }

    @Test
    @DisplayName("DIAG-03 without Micrometer on the classpath the violation is logged")
    void withoutMicrometerOnTheClasspathTheViolationIsLogged(CapturedOutput output) {
        runner.withClassLoader(new FilteredClassLoader(MeterRegistry.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ViolationReporter.Counter.class);

            context.getBean(ViolationReporter.class).report(violation(FORBIDDEN_HEADER, "tenant", EXTERNAL_CALL));

            assertThat(output.getOut()).contains("FORBIDDEN_HEADER");
        });
    }

    /** A clock that only moves when the test advances it. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("DIAG-04 the WARN entry includes the capture site after the thread")
    void theWarnEntryIncludesTheCaptureSiteAfterTheThread() {
        reporter.report(new Violation(MISSING_REQUIRED, "tenant", INTERNAL_CALL, Optional.empty(), "worker-1",
                Optional.of("com.example.Orders.place(Orders.java:42)"), Optional.empty()));

        assertThat(warnings()).singleElement().satisfies(entry -> assertThat(entry.message())
                .endsWith(", thread worker-1, capture site com.example.Orders.place(Orders.java:42)"));
    }
}
