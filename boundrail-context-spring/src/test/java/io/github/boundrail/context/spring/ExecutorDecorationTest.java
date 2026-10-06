package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Boundary.ATTACHED;
import static io.github.boundrail.context.Boundary.DETACHED;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.ContextRuntime;
import io.github.boundrail.context.ScopeListener;
import io.micrometer.context.ContextRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class ExecutorDecorationTest {

    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);
    private static final ContextKey<String> AUDIT = ContextKey.of("audit", String.class);

    /** What happened while the context started, in order. */
    private static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    /** The plain executor instances the bean methods created, by bean name. */
    private static final Map<String, Object> CREATED = new LinkedHashMap<>();

    /** What the decorated runnables of every {@link RecordingDecorator} saw when they ran. */
    private static final List<String> RUNS = new CopyOnWriteArrayList<>();

    /** A thread local that Micrometer's {@code ContextPropagatingTaskDecorator} propagates, through a registered accessor. */
    private static final ThreadLocal<String> LOCAL = new ThreadLocal<>();
    private static final String LOCAL_ACCESSOR = "boundrail-test.local";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class));

    @BeforeEach
    void clear() {
        EVENTS.clear();
        CREATED.clear();
        RUNS.clear();
        ContextRegistry.getInstance().registerThreadLocalAccessor(LOCAL_ACCESSOR, LOCAL::get, LOCAL::set, LOCAL::remove);
    }

    @AfterEach
    void removeAccessor() {
        ContextRegistry.getInstance().removeThreadLocalAccessor(LOCAL_ACCESSOR);
    }

    /** The bound tenant and audit values, or "-". */
    private static String seen() {
        return "tenant=" + TENANT.find().orElse("-") + " audit=" + AUDIT.find().orElse("-");
    }

    /** Submits a task from inside {@code tenant=acme, audit=a1} and returns what it saw. */
    private static String submittedFromBinding(AsyncTaskExecutor executor) throws Exception {
        return Context.with(TENANT, "acme").with(AUDIT, "a1").call(() -> executor.submit(ExecutorDecorationTest::seen))
                .get(5, TimeUnit.SECONDS);
    }

    @Configuration(proxyBeanMethods = false)
    static class SpringExecutors {

        @Bean
        ThreadPoolTaskExecutor pool() {
            return new ThreadPoolTaskExecutor();
        }

        @Bean
        SimpleAsyncTaskExecutor simple() {
            return new SimpleAsyncTaskExecutor("simple-");
        }

        @Bean
        ThreadPoolTaskScheduler scheduler() {
            return new ThreadPoolTaskScheduler();
        }
    }

    /** What a task on each executor type saw, including a task scheduled on the scheduler. */
    private Map<String, String> seenOnEachType(ApplicationContextRunner contextRunner) {
        Map<String, String> seen = new LinkedHashMap<>();
        contextRunner.withUserConfiguration(SpringExecutors.class).run(context -> {
            seen.put("ThreadPoolTaskExecutor", submittedFromBinding(context.getBean(ThreadPoolTaskExecutor.class)));
            seen.put("SimpleAsyncTaskExecutor", submittedFromBinding(context.getBean(SimpleAsyncTaskExecutor.class)));
            ThreadPoolTaskScheduler scheduler = context.getBean(ThreadPoolTaskScheduler.class);
            seen.put("ThreadPoolTaskScheduler", submittedFromBinding(scheduler));
            CompletableFuture<String> scheduled = new CompletableFuture<>();
            Context.with(TENANT, "acme").with(AUDIT, "a1")
                    .run(() -> scheduler.schedule(() -> scheduled.complete(seen()), Instant.now().plusMillis(10)));
            seen.put("scheduled task", scheduled.get(5, TimeUnit.SECONDS));
        });
        return seen;
    }

    @Test
    @DisplayName("EXE-03 ThreadPoolTaskExecutor, SimpleAsyncTaskExecutor and ThreadPoolTaskScheduler beans are decorated before initialisation, and none is with auto-decorate=false")
    void theThreeSpringExecutorTypesAreDecoratedAndNoneWithAutoDecorateFalse() {
        Map<String, String> decorated = seenOnEachType(runner);
        Map<String, String> notDecorated = seenOnEachType(runner.withPropertyValues("boundrail.context.executors.auto-decorate=false"));

        assertThat(decorated).containsExactly(
                Map.entry("ThreadPoolTaskExecutor", "tenant=acme audit=a1"),
                Map.entry("SimpleAsyncTaskExecutor", "tenant=acme audit=a1"),
                Map.entry("ThreadPoolTaskScheduler", "tenant=acme audit=a1"),
                Map.entry("scheduled task", "tenant=acme audit=a1"));
        assertThat(notDecorated).as("auto-decorate=false").containsOnlyKeys(decorated.keySet()).allSatisfy((type, seen) ->
                assertThat(seen).as(type).isEqualTo("tenant=- audit=-"));
    }

    @Test
    @DisplayName("EXE-02 decorated executors filter with the effective policy for detached")
    void decoratedExecutorsFilterWithTheEffectivePolicyForDetached() {
        ContextPolicy policy = ContextPolicy.builder()
                .key(TENANT, rules -> rules.propagateTo(ATTACHED, DETACHED))
                .key(AUDIT, rules -> rules.propagateTo(ATTACHED))
                .build();

        runner.withUserConfiguration(SpringExecutors.class).withBean(ContextPolicy.class, () -> policy).run(context ->
                assertThat(submittedFromBinding(context.getBean(ThreadPoolTaskExecutor.class))).isEqualTo("tenant=acme audit=-"));
    }

    @Configuration(proxyBeanMethods = false)
    static class PlainExecutors {

        @Bean
        ExecutorService plainService() {
            ExecutorService service = Executors.newSingleThreadExecutor();
            CREATED.put("plainService", service);
            return service;
        }

        @Bean
        Executor plainExecutor() {
            Executor executor = task -> {
                Thread thread = new Thread(task);
                thread.start();
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            CREATED.put("plainExecutor", executor);
            return executor;
        }
    }

    @Test
    @DisplayName("EXE-04 a plain Executor or ExecutorService bean is not wrapped automatically")
    void aPlainExecutorOrExecutorServiceBeanIsNotWrappedAutomatically() {
        runner.withUserConfiguration(PlainExecutors.class).run(context -> {
            ExecutorService service = context.getBean("plainService", ExecutorService.class);
            Executor executor = context.getBean("plainExecutor", Executor.class);
            List<String> seen = new CopyOnWriteArrayList<>();

            String fromService = Context.with(TENANT, "acme").call(() -> service.submit(ExecutorDecorationTest::seen))
                    .get(5, TimeUnit.SECONDS);
            Context.with(TENANT, "acme").run(() -> executor.execute(() -> seen.add(seen())));

            assertThat(service).isSameAs(CREATED.get("plainService"));
            assertThat(executor).isSameAs(CREATED.get("plainExecutor"));
            assertThat(fromService).isEqualTo("tenant=- audit=-");
            assertThat(seen).containsExactly("tenant=- audit=-");
        });
    }

    /** A pool that records when it is initialised, which happens after the post-processors' before-initialisation step. */
    static final class RecordingPool extends ThreadPoolTaskExecutor {
        @Override
        public void afterPropertiesSet() {
            EVENTS.add("executor initialised");
            super.afterPropertiesSet();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PoolThenPolicy {

        @Bean
        RecordingPool pool() {
            EVENTS.add("executor created");
            return new RecordingPool();
        }

        @Bean
        ContextPolicy policy() {
            EVENTS.add("policy created");
            return ContextPolicy.builder().key(TENANT, rules -> rules.propagateTo(ATTACHED, DETACHED)).build();
        }
    }

    @Test
    @DisplayName("EXE-03 the decoration does not create ContextPolicy beans during post-processor registration")
    void theDecorationDoesNotCreateContextPolicyBeansDuringPostProcessorRegistration() {
        runner.withUserConfiguration(PoolThenPolicy.class).run(context -> {
            assertThat(EVENTS).containsExactly("executor created", "executor initialised", "policy created");
            assertThat(submittedFromBinding(context.getBean(RecordingPool.class))).as("the pool is decorated").startsWith("tenant=acme");
        });
    }

    /** An existing decorator: counts its calls, and records what its decorated runnable sees when it runs. */
    static final class RecordingDecorator implements TaskDecorator {

        final AtomicInteger decorations = new AtomicInteger();

        @Override
        public Runnable decorate(Runnable task) {
            decorations.incrementAndGet();
            return () -> {
                RUNS.add(seen());
                task.run();
            };
        }
    }

    /** The bound tenant and audit values and the thread local's value, or "-". */
    private static String seenWithLocal() {
        return seen() + " local=" + (LOCAL.get() == null ? "-" : LOCAL.get());
    }

    /** Submits a task from inside {@code tenant=acme, audit=a1}, with the thread local set to {@code l1}, and returns what it saw. */
    private static String submittedFromBindingWithLocal(AsyncTaskExecutor executor) throws Exception {
        LOCAL.set("l1");
        try {
            return Context.with(TENANT, "acme").with(AUDIT, "a1").call(() -> executor.submit(ExecutorDecorationTest::seenWithLocal))
                    .get(5, TimeUnit.SECONDS);
        } finally {
            LOCAL.remove();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ExecutorsWithRecordingDecorator {

        @Bean
        ThreadPoolTaskExecutor pool() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setTaskDecorator(new RecordingDecorator());
            return executor;
        }

        @Bean
        SimpleAsyncTaskExecutor simple() {
            SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("simple-");
            executor.setTaskDecorator(new RecordingDecorator());
            return executor;
        }

        @Bean
        ThreadPoolTaskScheduler scheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setTaskDecorator(new RecordingDecorator());
            return scheduler;
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"pool", "simple", "scheduler"})
    @DisplayName("EXE-03 an existing decorator set in code runs inside the library's scope")
    void anExistingDecoratorSetInCodeRunsInsideTheLibrarysScope(String bean) {
        runner.withUserConfiguration(ExecutorsWithRecordingDecorator.class).run(context -> {
            String seen = submittedFromBinding(context.getBean(bean, AsyncTaskExecutor.class));

            assertThat(RUNS).as("what the existing decorator's runnable saw").containsExactly("tenant=acme audit=a1");
            assertThat(seen).as("what the task saw").isEqualTo("tenant=acme audit=a1");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {
    }

    /** Boot's executors and scheduler, built by Boot's builders, with a {@code TaskDecorator} bean, Micrometer's decorator, or both. */
    enum BootCase {
        TASK_DECORATOR_BEAN("applicationTaskExecutor", ThreadPoolTaskExecutor.class, true, false),
        PROPAGATE_CONTEXT("applicationTaskExecutor", ThreadPoolTaskExecutor.class, false, true),
        BOTH("applicationTaskExecutor", ThreadPoolTaskExecutor.class, true, true),
        VIRTUAL_THREADS("applicationTaskExecutor", SimpleAsyncTaskExecutor.class, true, true, "spring.threads.virtual.enabled=true"),
        TASK_SCHEDULER("taskScheduler", ThreadPoolTaskScheduler.class, true, true);

        final String bean;
        final Class<?> type;
        final boolean decoratorBean;
        final boolean propagateContext;
        final String[] properties;

        BootCase(String bean, Class<?> type, boolean decoratorBean, boolean propagateContext, String... properties) {
            this.bean = bean;
            this.type = type;
            this.decoratorBean = decoratorBean;
            this.propagateContext = propagateContext;
            this.properties = properties;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BootCase.class)
    @DisplayName("EXE-03 a decorator applied by Boot's builder from TaskDecorator beans is kept and runs inside the library's scope")
    void aDecoratorAppliedByBootsBuilderIsKeptAndRunsInsideTheLibrarysScope(BootCase bootCase) {
        ApplicationContextRunner boot = runner
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class, TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(Scheduling.class)
                .withPropertyValues(bootCase.properties)
                .withPropertyValues("spring.task.execution.propagate-context=" + bootCase.propagateContext);
        if (bootCase.decoratorBean) {
            boot = boot.withBean("recordingDecorator", TaskDecorator.class, RecordingDecorator::new);
        }

        boot.run(context -> {
            AsyncTaskExecutor executor = context.getBean(bootCase.bean, AsyncTaskExecutor.class);
            assertThat(executor).isExactlyInstanceOf(bootCase.type);

            String seen = submittedFromBindingWithLocal(executor);

            assertThat(seen).as("what the task saw")
                    .isEqualTo("tenant=acme audit=a1 local=" + (bootCase.propagateContext ? "l1" : "-"));
            assertThat(RUNS).as("what the TaskDecorator bean's runnable saw")
                    .isEqualTo(bootCase.decoratorBean ? List.of("tenant=acme audit=a1") : List.of());
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class ExecutorsWithMicrometerDecorator {

        @Bean
        ThreadPoolTaskExecutor pool() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
            return executor;
        }

        @Bean
        SimpleAsyncTaskExecutor simple() {
            SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("simple-");
            executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
            return executor;
        }

        @Bean
        ThreadPoolTaskScheduler scheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setTaskDecorator(new ContextPropagatingTaskDecorator());
            return scheduler;
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"pool", "simple", "scheduler"})
    @DisplayName("EXE-03 Micrometer's ContextPropagatingTaskDecorator still propagates its thread locals")
    void micrometersContextPropagatingTaskDecoratorStillPropagatesItsThreadLocals(String bean) {
        runner.withUserConfiguration(ExecutorsWithMicrometerDecorator.class).run(context ->
                assertThat(submittedFromBindingWithLocal(context.getBean(bean, AsyncTaskExecutor.class)))
                        .isEqualTo("tenant=acme audit=a1 local=l1"));
    }

    /** The three EXE-03 types, created outside a context with an existing decorator. */
    enum ExecutorType {
        THREAD_POOL_TASK_EXECUTOR {
            @Override
            AsyncTaskExecutor create(TaskDecorator decorator) {
                ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
                executor.setTaskDecorator(decorator);
                return executor;
            }
        },
        SIMPLE_ASYNC_TASK_EXECUTOR {
            @Override
            AsyncTaskExecutor create(TaskDecorator decorator) {
                SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("twice-");
                executor.setTaskDecorator(decorator);
                return executor;
            }
        },
        THREAD_POOL_TASK_SCHEDULER {
            @Override
            AsyncTaskExecutor create(TaskDecorator decorator) {
                ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
                scheduler.setTaskDecorator(decorator);
                return scheduler;
            }
        };

        abstract AsyncTaskExecutor create(TaskDecorator decorator);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ExecutorType.class)
    @DisplayName("EXE-03 an executor instance post-processed twice is decorated once")
    void anExecutorInstancePostProcessedTwiceIsDecoratedOnce(ExecutorType type) {
        runner.run(context -> {
            ExecutorDecoratingPostProcessor postProcessor = context.getBean(ExecutorDecoratingPostProcessor.class);
            RecordingDecorator existing = new RecordingDecorator();
            AsyncTaskExecutor executor = type.create(existing);
            Thread submitter = Thread.currentThread();
            AtomicInteger taskScopeEntries = new AtomicInteger();
            ScopeListener counting = entered -> {
                if (Thread.currentThread() != submitter) {
                    taskScopeEntries.incrementAndGet();
                }
                return () -> { };
            };
            postProcessor.postProcessBeforeInitialization(executor, "twice");
            postProcessor.postProcessBeforeInitialization(executor, "twice");
            if (executor instanceof InitializingBean initializing) {
                initializing.afterPropertiesSet();
            }
            ContextRuntime.addScopeListener(counting);
            try {
                String seen = submittedFromBinding(executor);

                assertThat(seen).isEqualTo("tenant=acme audit=a1");
                assertThat(taskScopeEntries).as("scope entries on the task's thread").hasValue(1);
                assertThat(existing.decorations).as("calls of the existing decorator").hasValue(1);
                assertThat(RUNS).as("runs of the existing decorator's runnable").containsExactly("tenant=acme audit=a1");
            } finally {
                ContextRuntime.removeScopeListener(counting);
                if (executor instanceof DisposableBean disposable) {
                    disposable.destroy();
                }
            }
        });
    }
}
