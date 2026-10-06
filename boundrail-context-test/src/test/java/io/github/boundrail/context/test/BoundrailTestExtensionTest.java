package io.github.boundrail.context.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.ContextRuntime;
import io.github.boundrail.context.ContextRuntime.CaptureSites;
import io.github.boundrail.context.ContextViolationException;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.spring.BoundrailClients;
import io.github.boundrail.context.spring.BoundrailContextAutoConfiguration;
import io.github.boundrail.context.spring.ViolationListener;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.web.client.RestClient;

/**
 * {@code @BoundrailTest} and the auto-configured violation recorder (TEST-02). Each test runs a sample test class through the JUnit Platform
 * {@code EngineTestKit}; the samples run only there.
 */
class BoundrailTestExtensionTest {

    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);
    private static final ContextKey<String> REGION = ContextKey.of("region", String.class);

    /** Nothing listens here, so a request that is sent fails to connect. */
    private static final URI UNREACHABLE = URI.create("http://127.0.0.1:1/");

    private static volatile boolean runningSamples;

    /** What the samples observed, in order. */
    private static final List<Object> OBSERVED = new CopyOnWriteArrayList<>();

    /** The contexts the samples report in. */
    private static final AtomicReference<ApplicationContext> TENANT_CONTEXT = new AtomicReference<>();
    private static final AtomicReference<ApplicationContext> REGION_CONTEXT = new AtomicReference<>();

    /** Whether a sample may run: only from {@link #run}. */
    static boolean runningSamples() {
        return runningSamples;
    }

    /** Runs the sample class through the Jupiter engine and returns its test events. */
    private static Events run(Class<?> sample) {
        OBSERVED.clear();
        runningSamples = true;
        try {
            return EngineTestKit.engine("junit-jupiter").selectors(selectClass(sample)).execute().testEvents();
        } finally {
            runningSamples = false;
        }
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

    /**
     * Calls an internal service through a client of the context, with no context bound, so the context reports one {@code MISSING_REQUIRED}
     * violation for its required key. Returns what the call threw.
     */
    private static Throwable callWithoutContext(ApplicationContext context) {
        RestClient.Builder builder = RestClient.builder().apply(BoundrailClients.internal("orders"));
        context.getBean(RestClientCustomizer.class).customize(builder);
        try {
            builder.build().get().uri(UNREACHABLE).retrieve().toBodilessEntity();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static List<String> keys(List<Violation> violations) {
        return violations.stream().map(violation -> violation.kind() + " " + violation.key()).toList();
    }

    @BoundrailTest
    @EnabledIf("io.github.boundrail.context.test.BoundrailTestExtensionTest#runningSamples")
    static class CaptureSitesSample {

        @Test
        void first() {
            OBSERVED.add(ContextRuntime.captureSites() + " " + ContextRuntime.sampleRate());
        }

        @Test
        void second() {
            OBSERVED.add(ContextRuntime.captureSites() + " " + ContextRuntime.sampleRate());
        }
    }

    @Test
    @DisplayName("TEST-02 @BoundrailTest turns capture sites on for the class and restores the previous mode and rate afterwards")
    void turnsCaptureSitesOnForTheClassAndRestoresThePreviousModeAndRate() {
        CaptureSites mode = ContextRuntime.captureSites();
        double rate = ContextRuntime.sampleRate();
        ContextRuntime.captureSites(CaptureSites.SAMPLED, 0.2);
        try {
            run(CaptureSitesSample.class).assertStatistics(stats -> stats.started(2).succeeded(2));

            assertThat(OBSERVED).containsExactly("ON 0.2", "ON 0.2");
            assertThat(ContextRuntime.captureSites()).as("mode after the class").isEqualTo(CaptureSites.SAMPLED);
            assertThat(ContextRuntime.sampleRate()).as("rate after the class").isEqualTo(0.2);
        } finally {
            ContextRuntime.captureSites(mode, rate);
        }
    }

    @BoundrailTest
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @EnabledIf("io.github.boundrail.context.test.BoundrailTestExtensionTest#runningSamples")
    static class RecordingSample {

        @BeforeAll
        static void reportBeforeTheClass() {
            callWithoutContext(REGION_CONTEXT.get());
        }

        /** A new instance is created for each test method, before it starts and between tests. */
        RecordingSample() {
            callWithoutContext(REGION_CONTEXT.get());
        }

        @Test
        @Order(1)
        void reportsInTwoContexts(RecordedViolations violations) {
            callWithoutContext(TENANT_CONTEXT.get());
            callWithoutContext(REGION_CONTEXT.get());
            OBSERVED.add(keys(violations.all()));
        }

        @Test
        @Order(2)
        void reportsNothing(RecordedViolations violations) {
            OBSERVED.add(keys(violations.all()));
        }
    }

    @Test
    @DisplayName("TEST-02 violations reported in any application context during a test method are recorded, and nothing outside one")
    void violationsReportedInAnyContextDuringATestMethodAreRecorded() {
        requiring(TENANT).run(tenantContext -> requiring(REGION).run(regionContext -> {
            TENANT_CONTEXT.set(tenantContext);
            REGION_CONTEXT.set(regionContext);

            run(RecordingSample.class).assertStatistics(stats -> stats.started(2).succeeded(2));

            assertThat(OBSERVED).containsExactly(
                    List.of("MISSING_REQUIRED tenant", "MISSING_REQUIRED region"),
                    List.of());
        }));
    }

    @BoundrailTest
    @EnabledIf("io.github.boundrail.context.test.BoundrailTestExtensionTest#runningSamples")
    static class OutsideSample {

        private static RecordedViolations received;

        @Test
        void receives(RecordedViolations violations) {
            received = violations;
            OBSERVED.add(violations.all());
        }

        @AfterAll
        static void callsAgain() {
            try {
                received.all();
                OBSERVED.add("no exception");
            } catch (RuntimeException e) {
                OBSERVED.add(e);
            }
        }
    }

    @Test
    @DisplayName("TEST-02 RecordedViolations.all() called while no @BoundrailTest test method is running throws IllegalStateException")
    void allOutsideATestMethodThrowsIllegalStateException() {
        run(OutsideSample.class).assertStatistics(stats -> stats.started(1).succeeded(1));

        assertThat(OBSERVED).hasSize(2);
        assertThat(OBSERVED.get(0)).as("inside the test method").isEqualTo(List.of());
        assertThat(OBSERVED.get(1)).as("from @AfterAll").isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("TEST-02 the test module's only auto-configuration is the recorder's ViolationListener")
    void theTestModulesOnlyAutoConfigurationIsTheRecordersViolationListener() {
        List<String> candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates();

        assertThat(candidates).as("auto-configurations of the test package")
                .filteredOn(name -> name.startsWith(BoundrailTestAutoConfiguration.class.getPackageName() + "."))
                .containsExactly(BoundrailTestAutoConfiguration.class.getName());
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(BoundrailTestAutoConfiguration.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(ViolationListener.class)).hasSize(1);
            List<String> moduleBeans = Arrays.stream(context.getBeanDefinitionNames())
                    .filter(name -> context.getType(name).getName().startsWith(BoundrailTestAutoConfiguration.class.getPackageName() + "."))
                    .filter(name -> !BoundrailTestAutoConfiguration.class.isAssignableFrom(context.getType(name)))
                    .toList();
            assertThat(moduleBeans).as("beans of the test module other than its configuration")
                    .containsExactlyElementsOf(context.getBeansOfType(ViolationListener.class).keySet());
        });
    }

    @BoundrailTest
    @EnabledIf("io.github.boundrail.context.test.BoundrailTestExtensionTest#runningSamples")
    static class ViolationModeSample {

        @Test
        void startsAContext() {
            requiring(TENANT).run(context -> OBSERVED.add(callWithoutContext(context)));
        }
    }

    @Test
    @DisplayName("TEST-02 the extension does not change the violation mode")
    void theExtensionDoesNotChangeTheViolationMode() {
        run(ViolationModeSample.class).assertStatistics(stats -> stats.started(1).succeeded(1));

        assertThat(OBSERVED).singleElement().as("what the call threw in a context started inside the class")
                .isInstanceOf(ContextViolationException.class);
    }
}
