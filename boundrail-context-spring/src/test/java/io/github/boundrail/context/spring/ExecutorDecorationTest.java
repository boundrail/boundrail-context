package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Boundary.ATTACHED;
import static io.github.boundrail.context.Boundary.DETACHED;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class ExecutorDecorationTest {

    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);
    private static final ContextKey<String> AUDIT = ContextKey.of("audit", String.class);

    /** What happened while the context started, in order. */
    private static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    /** The plain executor instances the bean methods created, by bean name. */
    private static final Map<String, Object> CREATED = new LinkedHashMap<>();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class));

    @BeforeEach
    void clear() {
        EVENTS.clear();
        CREATED.clear();
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
}
