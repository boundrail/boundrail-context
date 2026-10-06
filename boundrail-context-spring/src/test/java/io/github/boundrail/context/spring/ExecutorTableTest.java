package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Boundary.INTERNAL_CALL;
import static io.github.boundrail.context.Violation.Kind.MISSING_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.Violation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Executor evidence in violations (DIAG-04), from the table of executor beans (DIAG-12). */
@ExtendWith(OutputCaptureExtension.class)
class ExecutorTableTest {

    /** Factory methods of executor beans the table must not instantiate, in call order. */
    private static final List<String> CALLED = new CopyOnWriteArrayList<>();

    /** The executor evidence of a violation built while a bean was being created. */
    private static final AtomicReference<Optional<String>> EARLY = new AtomicReference<>();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class));

    @BeforeEach
    void clear() {
        CALLED.clear();
        EARLY.set(null);
    }

    /** The executor evidence of a violation the reporter builds on a thread with this name. */
    private static Optional<String> evidenceOn(String threadName, ViolationReporter reporter) throws InterruptedException {
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
    static class RawAndDecorated {

        @Bean
        ExecutorService rawExecutor() {
            return Executors.newSingleThreadExecutor();
        }

        @Bean
        ThreadPoolTaskExecutor workPool() {
            return pool("work-");
        }

        @Bean
        ThreadPoolTaskExecutor attachedPool() {
            return pool("attached-");
        }
    }

    @Test
    @DisplayName("DIAG-04 a violation on a thread of an executor bean names the bean and its status")
    void aViolationOnAThreadOfAnExecutorBeanNamesTheBeanAndItsStatus() {
        runner.withUserConfiguration(RawAndDecorated.class).withPropertyValues("boundrail.context.executors.modes.attachedPool=attached")
                .run(context -> {
                    ViolationReporter reporter = context.getBean(ViolationReporter.class);
                    Map<String, Optional<String>> evidence = new LinkedHashMap<>();
                    for (String thread : List.of("rawExecutor-1", "work-1", "attached-1", "unrelated-1")) {
                        evidence.put(thread, evidenceOn(thread, reporter));
                    }

                    assertThat(evidence).containsExactly(
                            Map.entry("rawExecutor-1", Optional.of("rawExecutor (GAP)")),
                            Map.entry("work-1", Optional.of("workPool (COVERED detached)")),
                            Map.entry("attached-1", Optional.of("attachedPool (COVERED attached)")),
                            Map.entry("unrelated-1", Optional.empty()));
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class OverlappingPrefixes {

        @Bean
        ThreadPoolTaskExecutor taskPool() {
            return pool("task-");
        }

        @Bean
        ThreadPoolTaskExecutor taskIoPool() {
            return pool("task-io-");
        }
    }

    @Test
    @DisplayName("DIAG-04 the longest matching prefix wins")
    void theLongestMatchingPrefixWins() {
        runner.withUserConfiguration(OverlappingPrefixes.class).run(context -> {
            ViolationReporter reporter = context.getBean(ViolationReporter.class);

            assertThat(evidenceOn("task-io-1", reporter)).contains("taskIoPool (COVERED detached)");
            assertThat(evidenceOn("task-1", reporter)).contains("taskPool (COVERED detached)");
        });
    }

    /** One executor instance registered under two bean names, with an explicit thread-name prefix. */
    @Configuration(proxyBeanMethods = false)
    static class OneInstanceTwoNames {

        final ThreadPoolTaskExecutor shared = pool("shared-");

        @Bean
        ThreadPoolTaskExecutor firstName() {
            return shared;
        }

        @Bean
        ThreadPoolTaskExecutor secondName() {
            return shared;
        }
    }

    @Test
    @DisplayName("DIAG-04 an executor instance decorated under two bean names is covered under both")
    void anExecutorInstanceDecoratedUnderTwoBeanNamesIsCoveredUnderBoth() {
        runner.withUserConfiguration(OneInstanceTwoNames.class).run(context -> {
            ViolationReporter reporter = context.getBean(ViolationReporter.class);

            assertThat(evidenceOn("firstName-1", reporter)).contains("firstName (COVERED detached)");
            assertThat(evidenceOn("secondName-1", reporter)).contains("secondName (COVERED detached)");
        });
    }

    /** Builds a violation on a {@code rawExecutor-1} thread while it is created, before the context has started. */
    static class EarlyViolation {
        EarlyViolation(ViolationReporter reporter) throws InterruptedException {
            EARLY.set(evidenceOn("rawExecutor-1", reporter));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RawThenEarlyViolation {

        @Bean
        ExecutorService rawExecutor() {
            return Executors.newSingleThreadExecutor();
        }

        @Bean
        EarlyViolation earlyViolation(ViolationReporter reporter) throws InterruptedException {
            return new EarlyViolation(reporter);
        }
    }

    @Test
    @DisplayName("DIAG-04 executor evidence is present once the context has started and absent before")
    void executorEvidenceIsPresentOnceTheContextHasStartedAndAbsentBefore() {
        runner.withUserConfiguration(RawThenEarlyViolation.class).run(context -> {
            Optional<String> afterStart = evidenceOn("rawExecutor-1", context.getBean(ViolationReporter.class));

            assertThat(EARLY.get()).as("during startup").isEmpty();
            assertThat(afterStart).as("after startup").contains("rawExecutor (GAP)");
        });
    }

    @Test
    @DisplayName("DIAG-04 the WARN entry includes the executor evidence after the capture site")
    void theWarnEntryIncludesTheExecutorEvidenceAfterTheCaptureSite(CapturedOutput output) {
        runner.run(context -> context.getBean(ViolationReporter.class).report(new Violation(MISSING_REQUIRED, "tenant", INTERNAL_CALL,
                Optional.empty(), "rawExecutor-1", Optional.of("com.example.Work.run(Work.java:7)"), Optional.of("rawExecutor (GAP)"))));

        assertThat(output.getOut()).contains("Context violation MISSING_REQUIRED: key tenant, boundary INTERNAL_CALL, thread rawExecutor-1, "
                + "capture site com.example.Work.run(Work.java:7), executor rawExecutor (GAP)");
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
    @DisplayName("DIAG-12 the table covers only instantiated singleton executor beans of the context itself, and instantiates none")
    void theTableCoversOnlyInstantiatedSingletonExecutorBeansOfTheContextItself() {
        try (AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext(ParentExecutor.class)) {
            runner.withParent(parent).withUserConfiguration(SingletonLazyAndPrototype.class).run(context -> {
                ViolationReporter reporter = context.getBean(ViolationReporter.class);
                Map<String, Optional<String>> evidence = new LinkedHashMap<>();
                for (String thread : List.of("rawExecutor-1", "lazyExecutor-1", "prototypeExecutor-1", "parentExecutor-1")) {
                    evidence.put(thread, evidenceOn(thread, reporter));
                }

                assertThat(evidence).containsExactly(
                        Map.entry("rawExecutor-1", Optional.of("rawExecutor (GAP)")),
                        Map.entry("lazyExecutor-1", Optional.empty()),
                        Map.entry("prototypeExecutor-1", Optional.empty()),
                        Map.entry("parentExecutor-1", Optional.empty()));
                assertThat(CALLED).as("factory methods called").isEmpty();
            });
        }
    }
}
