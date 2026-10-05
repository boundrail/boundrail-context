package io.github.boundrail.context.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.spring.BoundrailClients;
import io.github.boundrail.context.spring.BoundrailContextAutoConfiguration;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.AbstractAssert;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.web.client.RestClient;

/**
 * {@code ContextAssertions.assertThatViolations()} and {@code ViolationsAssert} (TEST-02, TEST-03, TEST-04, CORE-14). Sample test classes
 * under {@code @BoundrailTest} run through the JUnit Platform {@code EngineTestKit}, and only there.
 */
class ViolationsAssertTest {

    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);
    private static final ContextKey<String> REGION = ContextKey.of("region", String.class);

    /** Nothing listens here, so a request that is sent fails to connect. */
    private static final URI UNREACHABLE = URI.create("http://127.0.0.1:1/");

    private static volatile boolean runningSamples;

    /** What the samples observed, in order. */
    private static final List<Object> OBSERVED = new CopyOnWriteArrayList<>();

    /** The contexts the setup and teardown sample reports in. */
    private static final AtomicReference<ApplicationContext> TENANT_CONTEXT = new AtomicReference<>();
    private static final AtomicReference<ApplicationContext> REGION_CONTEXT = new AtomicReference<>();

    /** Whether a sample may run: only from {@link #run}. */
    static boolean runningSamples() {
        return runningSamples;
    }

    /** Runs the sample class through the Jupiter engine. */
    private static EngineExecutionResults run(Class<?> sample) {
        OBSERVED.clear();
        runningSamples = true;
        try {
            return EngineTestKit.engine("junit-jupiter").selectors(selectClass(sample)).execute();
        } finally {
            runningSamples = false;
        }
    }

    /** A violation of the kind for the key, as the running test's recording receives it. */
    private static void recordViolation(Violation.Kind kind, String key) {
        ViolationRecorder.record(new Violation(kind, key, Boundary.INTERNAL_CALL, Optional.empty(), "main", Optional.empty(),
                Optional.empty()));
    }

    /** A context with the library, the test module's auto-configuration and a policy that requires the key at INTERNAL_CALL. */
    private static ApplicationContextRunner requiring(ContextKey<String> key) {
        ContextPolicy policy = ContextPolicy.builder()
                .key(key, rules -> rules.codec(Codecs.string()).wireName(key.name()).required().propagateTo(Boundary.INTERNAL_CALL))
                .build();
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class, BoundrailTestAutoConfiguration.class))
                .withBean(ContextPolicy.class, () -> policy);
    }

    /** Calls an internal service through a client of the context with no context bound, so the context reports {@code MISSING_REQUIRED}. */
    private static void reportMissingRequired(ApplicationContext context) {
        RestClient.Builder builder = RestClient.builder().apply(BoundrailClients.internal("orders"));
        context.getBean(RestClientCustomizer.class).customize(builder);
        try {
            builder.build().get().uri(UNREACHABLE).retrieve().toBodilessEntity();
        } catch (RuntimeException expected) {
            // fail mode throws ContextViolationException after reporting
        }
    }

    @BoundrailTest
    @EnabledIf("io.github.boundrail.context.test.ViolationsAssertTest#runningSamples")
    static class AssertionsSample {

        @Test
        void withoutViolations() {
            ContextAssertions.assertThatViolations().isEmpty().hasSize(0);

            assertThatThrownBy(() -> ContextAssertions.assertThatViolations().hasSize(1)).isInstanceOf(AssertionError.class);
            assertThatThrownBy(() -> ContextAssertions.assertThatViolations().containsKind(Violation.Kind.MISSING_REQUIRED))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("MISSING_REQUIRED");
        }

        @Test
        void withOneViolation() {
            recordViolation(Violation.Kind.MISSING_REQUIRED, "tenant");

            ContextAssertions.assertThatViolations().hasSize(1).containsKind(Violation.Kind.MISSING_REQUIRED);

            assertThatThrownBy(() -> ContextAssertions.assertThatViolations().hasSize(2))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("MISSING_REQUIRED tenant");
            assertThatThrownBy(() -> ContextAssertions.assertThatViolations().containsKind(Violation.Kind.UNTRUSTED_HEADER))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("UNTRUSTED_HEADER")
                    .hasMessageContaining("MISSING_REQUIRED tenant");
            assertThatThrownBy(() -> ContextAssertions.assertThatViolations().isEmpty())
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("MISSING_REQUIRED tenant");
        }

        @Test
        void containsKindNull() {
            assertThatThrownBy(() -> ContextAssertions.assertThatViolations().containsKind(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    @DisplayName("TEST-03 assertThatViolations() checks hasSize, containsKind and isEmpty on the running test's violations")
    void assertThatViolationsChecksHasSizeContainsKindAndIsEmpty() {
        EngineExecutionResults results = run(AssertionsSample.class);

        results.testEvents().failed().stream()
                .forEach(event -> OBSERVED.add(event.getRequiredPayload(TestExecutionResult.class).getThrowable().orElseThrow()));
        assertThat(OBSERVED).as("failures of the sample tests").isEmpty();
        results.testEvents().assertStatistics(stats -> stats.started(3).succeeded(3));
    }

    @Test
    @DisplayName("TEST-04 ViolationsAssert extends AbstractAssert")
    void violationsAssertExtendsAbstractAssert() {
        assertThat(AbstractAssert.class).isAssignableFrom(ViolationsAssert.class);
    }

    @Test
    @DisplayName("CORE-14 containsKind(null) throws NullPointerException")
    void containsKindNullThrowsNullPointerException() {
        EngineExecutionResults results = run(AssertionsSample.class);

        assertThat(results.testEvents().succeeded().stream().map(event -> event.getTestDescriptor().getDisplayName()))
                .contains("containsKindNull()");
    }

    @BoundrailTest
    @EnabledIf("io.github.boundrail.context.test.ViolationsAssertTest#runningSamples")
    static class OutsideSample {

        @Test
        void inside() {
            ContextAssertions.assertThatViolations().isEmpty();
        }

        @AfterAll
        static void callsFromAfterAll() {
            try {
                ContextAssertions.assertThatViolations();
                OBSERVED.add("no exception");
            } catch (RuntimeException e) {
                OBSERVED.add(e);
            }
        }
    }

    @Test
    @DisplayName("TEST-02 assertThatViolations() called while no @BoundrailTest test method is running throws IllegalStateException")
    void assertThatViolationsOutsideATestMethodThrowsIllegalStateException() {
        assertThatThrownBy(ContextAssertions::assertThatViolations).as("from a plain test").isInstanceOf(IllegalStateException.class);

        run(OutsideSample.class).testEvents().assertStatistics(stats -> stats.started(1).succeeded(1));

        assertThat(OBSERVED).singleElement().as("from @AfterAll of a @BoundrailTest class").isInstanceOf(IllegalStateException.class);
    }

    @BoundrailTest
    @EnabledIf("io.github.boundrail.context.test.ViolationsAssertTest#runningSamples")
    static class SetupAndTeardownSample {

        @BeforeEach
        void reportInSetup() {
            reportMissingRequired(TENANT_CONTEXT.get());
        }

        @Test
        void reportsNothing() {
        }

        @AfterEach
        void reportInTeardown() {
            reportMissingRequired(REGION_CONTEXT.get());
            ContextAssertions.assertThatViolations().hasSize(2);
            OBSERVED.add(ViolationRecorder.all().stream().map(violation -> violation.kind() + " " + violation.key()).toList());
        }
    }

    @Test
    @DisplayName("TEST-02 a violation reported in @BeforeEach or @AfterEach is recorded for that test")
    void aViolationReportedInSetupOrTeardownIsRecordedForThatTest() {
        requiring(TENANT).run(tenantContext -> requiring(REGION).run(regionContext -> {
            TENANT_CONTEXT.set(tenantContext);
            REGION_CONTEXT.set(regionContext);

            run(SetupAndTeardownSample.class).testEvents().assertStatistics(stats -> stats.started(1).succeeded(1));

            assertThat(OBSERVED).containsExactly(List.of("MISSING_REQUIRED tenant", "MISSING_REQUIRED region"));
        }));
    }

    /** An extension whose {@code beforeAll} fails. */
    static final class FailingBeforeAll implements BeforeAllCallback {

        @Override
        public void beforeAll(ExtensionContext context) {
            throw new IllegalStateException("beforeAll failed");
        }
    }

    @ExtendWith(FailingBeforeAll.class)
    @BoundrailTest
    @EnabledIf("io.github.boundrail.context.test.ViolationsAssertTest#runningSamples")
    static class FailingBeforeAllSample {

        @Test
        void neverRuns() {
        }
    }

    @Test
    @DisplayName("TEST-02 when an earlier extension's beforeAll fails, @BoundrailTest's afterAll adds no exception of its own")
    void whenAnEarlierBeforeAllFailsAfterAllAddsNoExceptionOfItsOwn() {
        List<Throwable> failures = run(FailingBeforeAllSample.class).containerEvents().failed().stream()
                .map(event -> event.getRequiredPayload(TestExecutionResult.class).getThrowable().orElseThrow())
                .toList();

        assertThat(failures).singleElement().satisfies(failure -> {
            assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessage("beforeAll failed");
            assertThat(failure.getSuppressed()).as("suppressed exceptions").isEmpty();
        });
    }
}
