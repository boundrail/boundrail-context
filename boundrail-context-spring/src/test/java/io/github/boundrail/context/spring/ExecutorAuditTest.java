package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Boundary.INTERNAL_CALL;
import static io.github.boundrail.context.Violation.Kind.MISSING_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.boundrail.context.BoundExecutors;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The startup executor audit (DIAG-06) over the executor table (DIAG-12), and executor evidence for beans the audit also covers (DIAG-04).
 * The runner does not run {@code SpringApplication}, so each test publishes the {@code ApplicationReadyEvent} itself. Log entries are read from
 * the captured output, as level and message.
 */
@ExtendWith(OutputCaptureExtension.class)
class ExecutorAuditTest {

    /** Factory methods of executor beans the audit must not instantiate. */
    private static final List<String> CALLED = new CopyOnWriteArrayList<>();

    private static final Pattern LEVEL = Pattern.compile("\\b(TRACE|DEBUG|INFO|WARN|ERROR)\\b");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class));

    @BeforeEach
    void clear() {
        CALLED.clear();
    }

    /** Publishes the event {@code SpringApplication} publishes once the application is ready. */
    private static void ready(AssertableApplicationContext context) {
        context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context.getSourceApplicationContext(),
                Duration.ZERO));
    }

    /** The audit's log entries so far, each as {@code <level> <message>}. */
    private static List<String> auditEntries(CapturedOutput output) {
        return output.getOut().lines().filter(line -> line.contains("Executor audit: ")).map(line -> {
            Matcher level = LEVEL.matcher(line);
            return (level.find() ? level.group(1) : "?") + " " + line.substring(line.indexOf("Executor audit: "));
        }).toList();
    }

    /** The executor evidence of a violation the context's reporter builds on a thread with this name. */
    private static Optional<String> evidenceOn(String threadName, AssertableApplicationContext context) throws InterruptedException {
        ViolationReporter reporter = context.getBean(ViolationReporter.class);
        AtomicReference<Optional<String>> evidence = new AtomicReference<>();
        Thread thread = new Thread(() -> evidence.set(reporter.violation(MISSING_REQUIRED, "tenant", INTERNAL_CALL, Optional.empty())
                .executorEvidence()), threadName);
        thread.start();
        thread.join();
        return evidence.get();
    }

    private static ThreadPoolTaskExecutor pool(String threadNamePrefix) {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setThreadNamePrefix(threadNamePrefix);
        return pool;
    }

    @Configuration(proxyBeanMethods = false)
    static class ThreeExecutors {

        @Bean
        ExecutorService cExecutor() {
            return BoundExecutors.detached(Executors.newSingleThreadExecutor());
        }

        @Bean
        ExecutorService bExecutor() {
            return Executors.newSingleThreadExecutor();
        }

        @Bean
        ThreadPoolTaskExecutor aExecutor() {
            return new ThreadPoolTaskExecutor();
        }
    }

    @Test
    @DisplayName("DIAG-06 every executor bean is logged in bean-name order, then the summary")
    void everyExecutorBeanIsLoggedInBeanNameOrderThenTheSummary(CapturedOutput output) {
        runner.withUserConfiguration(ThreeExecutors.class)
                .withPropertyValues("boundrail.context.executors.modes.aExecutor=attached",
                        "boundrail.context.executors.modes.cExecutor=attached")
                .run(ExecutorAuditTest::ready);

        assertThat(auditEntries(output)).containsExactly(
                "INFO Executor audit: aExecutor COVERED (attached)",
                "WARN Executor audit: bExecutor GAP",
                "INFO Executor audit: cExecutor COVERED (detached)",
                "INFO Executor audit: 2 covered, 1 gaps",
                "WARN Executor audit: executors.modes.cExecutor matches no decorated executor");
    }

    @Configuration(proxyBeanMethods = false)
    static class OnePool {

        @Bean
        ThreadPoolTaskExecutor pool() {
            return new ThreadPoolTaskExecutor();
        }
    }

    @Test
    @DisplayName("DIAG-06 with auto-decorate=false the Spring executor types are gaps")
    void withAutoDecorateFalseTheSpringExecutorTypesAreGaps(CapturedOutput output) {
        runner.withUserConfiguration(OnePool.class).withPropertyValues("boundrail.context.executors.auto-decorate=false")
                .run(ExecutorAuditTest::ready);

        assertThat(auditEntries(output)).containsExactly(
                "WARN Executor audit: pool GAP",
                "INFO Executor audit: 0 covered, 1 gaps");
    }

    @Configuration(proxyBeanMethods = false)
    static class SingletonLazyAndPrototype {

        @Bean
        ExecutorService rawExecutor() {
            return Executors.newSingleThreadExecutor();
        }

        @Bean
        @Lazy
        ExecutorService lazyExecutor() {
            CALLED.add("lazyExecutor");
            return Executors.newSingleThreadExecutor();
        }

        @Bean
        @Scope("prototype")
        ExecutorService prototypeExecutor() {
            CALLED.add("prototypeExecutor");
            return Executors.newSingleThreadExecutor();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ParentExecutor {

        @Bean
        ExecutorService parentExecutor() {
            return Executors.newSingleThreadExecutor();
        }
    }

    @Test
    @DisplayName("DIAG-12 the audit lists only instantiated singleton executor beans of the context itself, and instantiates none")
    void theAuditListsOnlyInstantiatedSingletonExecutorBeansOfTheContextItself(CapturedOutput output) {
        try (AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext(ParentExecutor.class)) {
            runner.withParent(parent).withUserConfiguration(SingletonLazyAndPrototype.class).run(ExecutorAuditTest::ready);
        }

        assertThat(auditEntries(output)).containsExactly(
                "WARN Executor audit: rawExecutor GAP",
                "INFO Executor audit: 0 covered, 1 gaps");
        assertThat(CALLED).as("factory methods called").isEmpty();
    }

    @Test
    @DisplayName("DIAG-04 a BoundExecutors bean is covered in executor evidence")
    void aBoundExecutorsBeanIsCoveredInExecutorEvidence() {
        runner.withUserConfiguration(ThreeExecutors.class).run(context ->
                assertThat(evidenceOn("cExecutor-1", context)).contains("cExecutor (COVERED detached)"));
    }

    @Configuration(proxyBeanMethods = false)
    static class SharedAndEmptyPrefixes {

        @Bean
        ThreadPoolTaskExecutor zPool() {
            return pool("same-");
        }

        @Bean
        ThreadPoolTaskExecutor aPool() {
            return pool("same-");
        }

        @Bean
        ThreadPoolTaskExecutor emptyPrefixPool() {
            return pool("");
        }
    }

    @Test
    @DisplayName("DIAG-04 equally long prefixes go to the first bean name in ascending order, and an empty prefix matches nothing")
    void equallyLongPrefixesGoToTheFirstBeanNameAndAnEmptyPrefixMatchesNothing() {
        runner.withUserConfiguration(SharedAndEmptyPrefixes.class).run(context -> {
            assertThat(evidenceOn("same-1", context)).contains("aPool (COVERED detached)");
            assertThat(evidenceOn("unrelated-1", context)).isEmpty();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class FactoryBeanExecutor {

        @Bean
        ExecutorService rawExecutor() {
            return Executors.newSingleThreadExecutor();
        }

        @Bean
        ThreadPoolExecutorFactoryBean factoryExecutor() {
            return new ThreadPoolExecutorFactoryBean();
        }
    }

    @Test
    @DisplayName("DIAG-12 an executor produced by a FactoryBean is skipped")
    void anExecutorProducedByAFactoryBeanIsSkipped(CapturedOutput output) {
        runner.withUserConfiguration(FactoryBeanExecutor.class).run(context -> {
            ready(context);

            assertThat(evidenceOn("factoryExecutor-1", context)).isEmpty();
        });

        assertThat(auditEntries(output)).containsExactly(
                "WARN Executor audit: rawExecutor GAP",
                "INFO Executor audit: 0 covered, 1 gaps");
    }

    @Test
    @DisplayName("DIAG-06 a modes key that matches no decorated executor is logged at WARN")
    void aModesKeyThatMatchesNoDecoratedExecutorIsLoggedAtWarn(CapturedOutput output) {
        runner.withUserConfiguration(OnePool.class)
                .withPropertyValues("boundrail.context.executors.modes.rawExecuter=attached",
                        "boundrail.context.executors.modes.POOL=attached")
                .run(ExecutorAuditTest::ready);

        assertThat(auditEntries(output)).containsExactly(
                "INFO Executor audit: pool COVERED (attached)",
                "INFO Executor audit: 1 covered, 0 gaps",
                "WARN Executor audit: executors.modes.rawExecuter matches no decorated executor");
    }

    @Configuration(proxyBeanMethods = false)
    static class LazyAndPrototypePools {

        @Bean
        @Lazy
        SimpleAsyncTaskExecutor lazyPool() {
            CALLED.add("lazyPool");
            return new SimpleAsyncTaskExecutor();
        }

        @Bean
        @Scope("prototype")
        ThreadPoolTaskExecutor jobPool() {
            CALLED.add("jobPool");
            return new ThreadPoolTaskExecutor();
        }
    }

    @Test
    @DisplayName("DIAG-06 a modes key naming a lazy or prototype executor bean definition is not reported as unmatched")
    void aModesKeyNamingALazyOrPrototypeExecutorBeanDefinitionIsNotReportedAsUnmatched(CapturedOutput output) {
        runner.withUserConfiguration(LazyAndPrototypePools.class)
                .withPropertyValues("boundrail.context.executors.modes.lazyPool=attached", "boundrail.context.executors.modes.jobPool=attached",
                        "boundrail.context.executors.modes.nonePool=attached")
                .run(context -> {
                    ready(context);

                    // Checked before the context closes: closing it makes Spring create the prototype pool, an ApplicationListener.
                    assertThat(CALLED).as("factory methods called").isEmpty();
                });

        assertThat(auditEntries(output)).containsExactly(
                "INFO Executor audit: 0 covered, 0 gaps",
                "WARN Executor audit: executors.modes.nonePool matches no decorated executor");
    }

    @Test
    @DisplayName("DIAG-06 the audit flags are bound as Boot booleans")
    void theAuditFlagsAreBoundAsBootBooleans(CapturedOutput output) {
        List<String> failedToStart = new ArrayList<>();
        for (String flag : List.of("enabled", "fail-on-gap")) {
            for (boolean lazy : List.of(false, true)) {
                ApplicationContextRunner invalid = runner.withPropertyValues("boundrail.context.audit." + flag + "=maybe");
                if (lazy) {
                    invalid = invalid.withPropertyValues("spring.main.lazy-initialization=true").withInitializer(
                            context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));
                }
                invalid.run(context -> {
                    if (context.getStartupFailure() != null) {
                        failedToStart.add(flag + "=maybe, lazy initialization " + lazy);
                    }
                });
            }
        }

        runner.withUserConfiguration(OnePool.class).withPropertyValues("boundrail.context.audit.enabled=yes").run(ExecutorAuditTest::ready);
        List<String> withYes = auditEntries(output);
        runner.withUserConfiguration(OnePool.class).withPropertyValues("boundrail.context.audit.enabled=FALSE").run(ExecutorAuditTest::ready);
        List<String> withYesThenFalse = auditEntries(output);

        assertThat(withYes).as("audit.enabled=yes").containsExactly(
                "INFO Executor audit: pool COVERED (detached)",
                "INFO Executor audit: 1 covered, 0 gaps");
        assertThat(withYesThenFalse).as("audit.enabled=FALSE adds no entries").isEqualTo(withYes);
        assertThat(failedToStart).containsExactly(
                "enabled=maybe, lazy initialization false",
                "enabled=maybe, lazy initialization true",
                "fail-on-gap=maybe, lazy initialization false",
                "fail-on-gap=maybe, lazy initialization true");
    }

    @Test
    @DisplayName("DIAG-06 with audit.fail-on-gap=true and a gap startup fails with a message listing every gap")
    void withFailOnGapAndAGapStartupFailsWithAMessageListingEveryGap() {
        AtomicReference<Throwable> withGaps = new AtomicReference<>();
        AtomicReference<Throwable> withoutGaps = new AtomicReference<>();

        runner.withUserConfiguration(ThreeExecutors.class).withPropertyValues("boundrail.context.audit.fail-on-gap=true")
                .withBean("dExecutor", ExecutorService.class, Executors::newSingleThreadExecutor)
                .run(context -> withGaps.set(catchThrowable(() -> ready(context))));
        runner.withUserConfiguration(OnePool.class).withPropertyValues("boundrail.context.audit.fail-on-gap=true")
                .run(context -> withoutGaps.set(catchThrowable(() -> ready(context))));

        assertThat(withGaps.get()).isInstanceOf(IllegalStateException.class).hasMessageContaining("bExecutor")
                .hasMessageContaining("dExecutor").hasMessageNotContaining("aExecutor").hasMessageNotContaining("cExecutor");
        assertThat(withoutGaps.get()).as("without a gap").isNull();
    }

    @Configuration(proxyBeanMethods = false)
    static class RawExecutor {

        @Bean
        ExecutorService rawExecutor() {
            return Executors.newSingleThreadExecutor();
        }
    }

    @Test
    @DisplayName("DIAG-06 with audit.enabled=false nothing is audited and executor evidence is still filled")
    void withAuditDisabledNothingIsAuditedAndExecutorEvidenceIsStillFilled(CapturedOutput output) {
        runner.withUserConfiguration(RawExecutor.class).withPropertyValues("boundrail.context.audit.enabled=false").run(context -> {
            ready(context);

            assertThat(evidenceOn("rawExecutor-1", context)).contains("rawExecutor (GAP)");
        });

        assertThat(auditEntries(output)).isEmpty();
    }
}
