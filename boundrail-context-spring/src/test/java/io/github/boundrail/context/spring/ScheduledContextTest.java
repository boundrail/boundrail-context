package io.github.boundrail.context.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextRuntime;
import io.github.boundrail.context.ContextRuntime.CaptureSites;
import io.github.boundrail.context.ContextSnapshot;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

/** {@code @Scheduled} methods run in a fresh system context when the scheduler invokes them (EXE-06). */
@ExtendWith(OutputCaptureExtension.class)
class ScheduledContextTest {

    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);
    private static final String PROCESSOR = TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
    private static final String SYSTEM_CONTEXT = "boundrail.initiator=system:scheduled:cleanupJob.run";

    /** What each run of {@code cleanupJob.run} saw, as {@code <thread>|<bound keys>}. */
    private static final BlockingQueue<String> RUNS = new LinkedBlockingQueue<>();

    /** What each run of the job under an application's own processor saw, kept apart so no other context's run can land in it. */
    private static final BlockingQueue<String> OWN_PROCESSOR_RUNS = new LinkedBlockingQueue<>();

    /** The capture site of the snapshot the latest run saw. */
    private static volatile Optional<String> lastCaptureSite = Optional.empty();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class));

    private CaptureSites savedMode;
    private double savedRate;

    @BeforeEach
    void clear() {
        RUNS.clear();
        OWN_PROCESSOR_RUNS.clear();
        lastCaptureSite = Optional.empty();
        savedMode = ContextRuntime.captureSites();
        savedRate = ContextRuntime.sampleRate();
    }

    @AfterEach
    void restoreCaptureSites() {
        ContextRuntime.captureSites(savedMode, savedRate);
    }

    /** The bound keys of a snapshot as {@code name=value}, in name order, comma-separated; empty if none is bound. */
    private static String describe(ContextSnapshot snapshot) {
        return snapshot.keys().stream().sorted(Comparator.comparing(ContextKey::name))
                .map(key -> key.name() + "=" + snapshot.find(key).orElseThrow())
                .collect(Collectors.joining(", "));
    }

    /** A job whose scheduled method records what it saw. */
    static class CleanupJob {

        @Scheduled(fixedDelay = 20)
        void run() {
            ContextSnapshot seen = Context.capture();
            lastCaptureSite = seen.captureSite();
            RUNS.add(Thread.currentThread().getName() + "|" + describe(seen));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {

        @Bean
        CleanupJob cleanupJob() {
            return new CleanupJob();
        }
    }

    /** What the next run of {@code cleanupJob.run} on a thread other than the test's saw, waiting up to five seconds. */
    private static String nextScheduledRun() throws InterruptedException {
        return nextScheduledRun(RUNS);
    }

    /** What the next run recorded to the queue on a thread other than the test's saw, waiting up to five seconds. */
    private static String nextScheduledRun(BlockingQueue<String> runs) throws InterruptedException {
        String self = Thread.currentThread().getName() + "|";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            String run = runs.poll(100, TimeUnit.MILLISECONDS);
            if (run != null && !run.startsWith(self)) {
                return run.substring(run.indexOf('|') + 1);
            }
        }
        throw new AssertionError("cleanupJob.run was not run by the scheduler within five seconds");
    }

    /** What a direct call of {@code cleanupJob.run} on this thread saw. */
    private static String directCall(AssertableApplicationContext context) {
        String self = Thread.currentThread().getName() + "|";
        RUNS.clear();
        Context.with(TENANT, "acme").run(() -> context.getBean(CleanupJob.class).run());
        return RUNS.stream().filter(run -> run.startsWith(self)).map(run -> run.substring(self.length())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("EXE-06 a @Scheduled method invoked by the scheduler runs with exactly initiator=system:scheduled:<beanName>.<methodName>, and a direct call keeps the caller's context")
    void aScheduledMethodInvokedByTheSchedulerRunsWithExactlyTheSystemInitiator() {
        runner.withUserConfiguration(Scheduling.class).run(context -> {
            String scheduled = nextScheduledRun();
            String direct = directCall(context);

            assertThat(scheduled).as("invoked by the scheduler").isEqualTo(SYSTEM_CONTEXT);
            assertThat(direct).as("called directly inside tenant=acme").isEqualTo("tenant=acme");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingOnOneThread {

        @Bean
        CleanupJob cleanupJob() {
            return new CleanupJob();
        }

        @Bean
        ThreadPoolTaskScheduler taskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            return scheduler;
        }
    }

    @Test
    @DisplayName("EXE-06 a @Scheduled method never sees context left over from another task")
    void aScheduledMethodNeverSeesContextLeftOverFromAnotherTask() {
        // The context starts inside a binding, so the decorated scheduler captures tenant=leak when the task is scheduled.
        Context.with(TENANT, "leak").run(() -> runner.withUserConfiguration(SchedulingOnOneThread.class).run(context ->
                assertThat(nextScheduledRun()).isEqualTo(SYSTEM_CONTEXT)));
    }

    @Test
    @DisplayName("DIAG-05 under on the @Scheduled binding records a capture site in the library's scheduled support")
    void underOnTheScheduledBindingRecordsACaptureSiteInTheLibrarysScheduledSupport() {
        ContextRuntime.captureSites(CaptureSites.ON, savedRate);

        runner.withUserConfiguration(Scheduling.class).run(context -> {
            nextScheduledRun();

            assertThat(lastCaptureSite).hasValueSatisfying(site ->
                    assertThat(site).startsWith("io.github.boundrail.context.spring.ContextScheduledAnnotationBeanPostProcessor"));
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnProcessor {

        @Bean(name = TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)
        static ScheduledAnnotationBeanPostProcessor ownScheduledAnnotationProcessor() {
            return new ScheduledAnnotationBeanPostProcessor();
        }

        @Bean
        OwnProcessorJob ownProcessorJob() {
            return new OwnProcessorJob();
        }
    }

    /** A job scheduled by an application's own processor; records to its own queue. */
    static class OwnProcessorJob {

        @Scheduled(fixedDelay = 20)
        void run() {
            OWN_PROCESSOR_RUNS.add(Thread.currentThread().getName() + "|" + describe(Context.capture()));
        }
    }

    /** The startup WARN entries that name the scheduled-annotation processor's bean name. */
    private static List<String> processorWarnings(CapturedOutput output) {
        return output.getOut().lines().filter(line -> line.contains("WARN") && line.contains(PROCESSOR)).toList();
    }

    @Test
    @DisplayName("EXE-06 only Spring's own scheduled-annotation post-processor definition is replaced, and any other is left with one startup WARN")
    void onlySpringsOwnScheduledAnnotationPostProcessorDefinitionIsReplaced(CapturedOutput output) {
        runner.withUserConfiguration(Scheduling.class).run(context -> {
            assertThat(context.getBean(PROCESSOR)).as("with @EnableScheduling").isInstanceOf(ContextScheduledAnnotationBeanPostProcessor.class);
            assertThat(processorWarnings(output)).as("WARN entries with @EnableScheduling").isEmpty();
        });

        runner.withUserConfiguration(OwnProcessor.class).run(context -> {
            assertThat(context.getBean(PROCESSOR)).as("the application's own").isExactlyInstanceOf(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(processorWarnings(output)).as("WARN entries with the application's own").hasSize(1);
            assertThat(nextScheduledRun(OWN_PROCESSOR_RUNS)).as("its scheduled method").isEmpty();
        });

        int before = processorWarnings(output).size();
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(PROCESSOR);
            assertThat(processorWarnings(output)).as("WARN entries with neither").hasSize(before);
        });
    }

    @Test
    @DisplayName("EXE-06 the scheduled task keeps its description")
    void theScheduledTaskKeepsItsDescription() {
        runner.withUserConfiguration(Scheduling.class).run(context -> {
            ScheduledTask task = context.getBean(PROCESSOR, ScheduledTaskHolder.class).getScheduledTasks().stream()
                    .filter(scheduled -> scheduled.toString().endsWith("CleanupJob.run")).findFirst().orElseThrow();
            Runnable runnable = task.getTask().getRunnable();
            String self = Thread.currentThread().getName() + "|";
            RUNS.clear();
            Context.with(TENANT, "acme").run(runnable);
            String seen = RUNS.stream().filter(run -> run.startsWith(self)).map(run -> run.substring(self.length())).findFirst()
                    .orElseThrow();

            assertThat(runnable.toString()).isEqualTo(CleanupJob.class.getName() + ".run");
            assertThat(seen).as("the task's runnable, run inside tenant=acme").isEqualTo(SYSTEM_CONTEXT);
        });
    }
}
