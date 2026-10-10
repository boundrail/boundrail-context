package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Boundary.ATTACHED;
import static io.github.boundrail.context.Boundary.DETACHED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.boundrail.context.Context;
import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.ContextRuntime;
import io.github.boundrail.context.Deadline;
import io.github.boundrail.context.DeadlineExceededException;
import io.github.boundrail.context.ScopeListener;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.Ordered;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** A {@code Callable} or {@code WebAsyncTask} returned by a handler runs in the request's snapshot, filtered for {@code ATTACHED} (EXE-09). */
class MvcCallableTest {

    /** Propagates to {@code ATTACHED} only. */
    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);
    /** Propagates to {@code DETACHED} only, not to {@code ATTACHED}. */
    private static final ContextKey<String> SESSION = ContextKey.of("session", String.class);

    private static final ContextPolicy POLICY = ContextPolicy.builder()
            .key(TENANT, rules -> rules.propagateTo(ATTACHED))
            .key(SESSION, rules -> rules.propagateTo(DETACHED))
            .build();

    private static final long TIMEOUT_MILLIS = 5_000;

    /** The bound tenant and session, or "-", and the name of the current thread. */
    private static String seen() {
        return "tenant=" + TENANT.find().orElse("-") + " session=" + SESSION.find().orElse("-") + " thread=" + Thread.currentThread().getName();
    }

    /** Async handlers: a {@code Callable}, and {@code WebAsyncTask}s without an executor of their own. */
    @RestController
    static final class AsyncController {

        final AtomicBoolean completed = new AtomicBoolean();
        final CountDownLatch release = new CountDownLatch(1);

        @GetMapping("/callable")
        Callable<String> callable() {
            return MvcCallableTest::seen;
        }

        @GetMapping("/web-async-task")
        WebAsyncTask<String> webAsyncTask() {
            WebAsyncTask<String> task = new WebAsyncTask<>(MvcCallableTest::seen);
            task.onCompletion(() -> completed.set(true));
            return task;
        }

        /**
         * Blocks until released, so only the timeout produces a result. Spring interrupts the task when the timeout fires, before it runs
         * the timeout callback, so the task keeps waiting through interrupts: finishing on one would race the callback for the result.
         */
        @GetMapping("/web-async-task/timeout")
        WebAsyncTask<String> webAsyncTaskTimingOut() {
            WebAsyncTask<String> task = new WebAsyncTask<>(60_000L, () -> {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
                while (release.getCount() > 0 && System.nanoTime() < deadline) {
                    try {
                        release.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                    }
                    catch (InterruptedException interrupted) {
                        // Keep waiting: see above.
                    }
                }
                return "released";
            });
            task.onTimeout(() -> "timed out");
            return task;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class Mvc {

        @Bean
        AsyncController asyncController() {
            return new AsyncController();
        }
    }

    /** MVC's executor: a plain {@code SimpleAsyncTaskExecutor} that is not a bean, so the library does not decorate it. */
    @Configuration(proxyBeanMethods = false)
    static class PlainMvcExecutor implements WebMvcConfigurer {

        @Override
        public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
            configurer.setTaskExecutor(new SimpleAsyncTaskExecutor("plainMvc-"));
        }
    }

    /** MVC's executor: a {@code ThreadPoolTaskExecutor} bean, which the library decorates in its mode, {@code detached} by default. */
    @Configuration(proxyBeanMethods = false)
    static class DetachedMvcExecutor implements WebMvcConfigurer {

        private final ThreadPoolTaskExecutor mvcExecutor;

        DetachedMvcExecutor(ThreadPoolTaskExecutor mvcExecutor) {
            this.mvcExecutor = mvcExecutor;
        }

        @Override
        public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
            configurer.setTaskExecutor(mvcExecutor);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MvcExecutorBean {

        @Bean
        ThreadPoolTaskExecutor mvcExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setThreadNamePrefix("mvcExecutor-");
            return executor;
        }
    }

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
            .withBean(ContextPolicy.class, () -> POLICY)
            .withUserConfiguration(Mvc.class);

    private static MockMvc mockMvc(AssertableWebApplicationContext context) {
        assertThat(context).hasNotFailed();
        return MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext()).build();
    }

    /** Starts the request from inside {@code tenant=acme, session=s1}, so that is the request's snapshot when MVC submits the task. */
    private static MvcResult startInRequestSnapshot(MockMvc mockMvc, String path) throws Exception {
        return Context.with(TENANT, "acme").with(SESSION, "s1").call(() -> mockMvc.perform(get(path)).andReturn());
    }

    @Test
    @DisplayName("EXE-09 a Callable returned by a handler runs in the request's snapshot filtered for attached")
    void aCallableRunsInTheRequestsSnapshotFilteredForAttached() {
        runner.withUserConfiguration(PlainMvcExecutor.class).run(context -> {
            MvcResult started = startInRequestSnapshot(mockMvc(context), "/callable");

            assertThat(started.getAsyncResult(TIMEOUT_MILLIS)).isEqualTo("tenant=acme session=- thread=plainMvc-1");
        });
    }

    @Test
    @DisplayName("EXE-09 this holds whatever the mode of MVC's executor")
    void thisHoldsWhateverTheModeOfMvcsExecutor() {
        runner.withUserConfiguration(MvcExecutorBean.class, DetachedMvcExecutor.class).run(context -> {
            assertThat(context.getBean(ExecutorDecoratingPostProcessor.class).decoratedMode("mvcExecutor")).as("MVC executor's mode")
                    .contains(DETACHED);

            MvcResult started = startInRequestSnapshot(mockMvc(context), "/callable");

            assertThat(started.getAsyncResult(TIMEOUT_MILLIS)).isEqualTo("tenant=acme session=- thread=mvcExecutor-1");
        });
    }

    @Test
    @DisplayName("EXE-09 this holds with MVC's internal default executor")
    void thisHoldsWithMvcsInternalDefaultExecutor() {
        runner.run(context -> {
            MvcResult started = startInRequestSnapshot(mockMvc(context), "/callable");

            assertThat((String) started.getAsyncResult(TIMEOUT_MILLIS)).startsWith("tenant=acme session=- thread=MvcAsync");
        });
    }

    @Test
    @DisplayName("EXE-09 a WebAsyncTask without its own executor runs in the same snapshot, and its onCompletion and timeout callbacks still fire")
    void aWebAsyncTaskRunsInTheSameSnapshotAndItsCallbacksStillFire() {
        runner.run(context -> {
            MockMvc mockMvc = mockMvc(context);
            AsyncController controller = context.getBean(AsyncController.class);

            MvcResult started = startInRequestSnapshot(mockMvc, "/web-async-task");
            assertThat((String) started.getAsyncResult(TIMEOUT_MILLIS)).startsWith("tenant=acme session=- thread=MvcAsync");
            mockMvc.perform(asyncDispatch(started));
            assertThat(controller.completed).as("onCompletion fired").isTrue();

            MvcResult timingOut = startInRequestSnapshot(mockMvc, "/web-async-task/timeout");
            try {
                MockHttpServletRequest request = (MockHttpServletRequest) timingOut.getRequest();
                MockAsyncContext asyncContext = (MockAsyncContext) request.getAsyncContext();
                for (AsyncListener listener : asyncContext.getListeners()) {
                    listener.onTimeout(new AsyncEvent(asyncContext));
                }
                assertThat(WebAsyncUtils.getAsyncManager(request).getConcurrentResult()).as("result of the timeout callback")
                        .isEqualTo("timed out");
            } finally {
                controller.release.countDown();
            }
        });
    }

    /** Handlers whose tasks set a flag when they are called. */
    @RestController
    static final class FlagController {

        final AtomicBoolean callableCalled = new AtomicBoolean();
        final AtomicBoolean webAsyncTaskCalled = new AtomicBoolean();

        @GetMapping("/flag/callable")
        Callable<String> callable() {
            return () -> {
                callableCalled.set(true);
                return "called";
            };
        }

        @GetMapping("/flag/web-async-task")
        WebAsyncTask<String> webAsyncTask() {
            return new WebAsyncTask<>(() -> {
                webAsyncTaskCalled.set(true);
                return "called";
            });
        }
    }

    /** MVC's executor: one thread, which a test can block until the request's deadline has expired. */
    @Configuration(proxyBeanMethods = false)
    static class BlockableMvcExecutor implements WebMvcConfigurer {

        final ExecutorService thread = Executors.newSingleThreadExecutor(task -> new Thread(task, "blockableMvc-1"));

        @Bean
        FlagController flagController() {
            return new FlagController();
        }

        @Override
        public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
            configurer.setTaskExecutor(new ConcurrentTaskExecutor(thread));
        }

        @Bean(destroyMethod = "")
        BlockableMvcExecutor.Shutdown blockableShutdown() {
            return new Shutdown(thread);
        }

        /** Shuts the thread down with the context. */
        record Shutdown(ExecutorService thread) implements org.springframework.beans.factory.DisposableBean {
            @Override
            public void destroy() {
                thread.shutdownNow();
            }
        }
    }

    /** The application's own Callable interceptor, recording the calls it gets. */
    @Configuration(proxyBeanMethods = false)
    static class ApplicationCallableInterceptor implements WebMvcConfigurer {

        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
            configurer.registerCallableInterceptors(new CallableProcessingInterceptor() {
                @Override
                public <T> void preProcess(NativeWebRequest request, Callable<T> task) {
                    calls.add("preProcess");
                }

                @Override
                public <T> void postProcess(NativeWebRequest request, Callable<T> task, Object result) {
                    calls.add("postProcess " + (result instanceof DeadlineExceededException expired ? expired.stage() : result));
                }
            });
        }
    }

    /**
     * Starts the request inside a 50ms deadline while MVC's thread is blocked, releases the thread once the deadline has expired, and returns
     * the started request.
     */
    private static MvcResult startAfterExpiry(MockMvc mockMvc, ExecutorService mvcThread, String path) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        mvcThread.submit(() -> release.await(5, TimeUnit.SECONDS));
        Deadline deadline = Deadline.after(Duration.ofMillis(50));
        MvcResult started = Context.with(BuiltInKeys.DEADLINE, deadline).call(() -> mockMvc.perform(get(path)).andReturn());
        while (!deadline.isExpired()) {
            Thread.sleep(5);
        }
        release.countDown();
        return started;
    }

    private static MvcResult startWithTimeLeft(MockMvc mockMvc, String path) throws Exception {
        return Context.with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ofSeconds(10))).call(() -> mockMvc.perform(get(path)).andReturn());
    }

    /** Asserts that the concurrent result is {@code DeadlineExceededException} at {@code EXECUTOR} and that the async dispatch answers 504. */
    private static void assertRefused(MockMvc mockMvc, MvcResult started) throws Exception {
        Object result = started.getAsyncResult(TIMEOUT_MILLIS);
        assertThat(result).isInstanceOf(DeadlineExceededException.class);
        assertThat(((DeadlineExceededException) result).stage()).isEqualTo(DeadlineExceededException.Stage.EXECUTOR);
        assertThat(mockMvc.perform(asyncDispatch(started)).andReturn().getResponse().getStatus()).as("mapped by DL-08").isEqualTo(504);
    }

    @Test
    @DisplayName("EXE-08 a Callable that starts after its captured deadline has expired is not called, and the request completes with the"
            + " exception, which DL-08 maps")
    void aCallableThatStartsAfterItsDeadlineHasExpiredIsNotCalled() {
        runner.withUserConfiguration(BlockableMvcExecutor.class).run(context -> {
            MockMvc mockMvc = mockMvc(context);
            FlagController controller = context.getBean(FlagController.class);
            ExecutorService mvcThread = context.getBean(BlockableMvcExecutor.class).thread;

            MvcResult expired = startAfterExpiry(mockMvc, mvcThread, "/flag/callable");
            assertRefused(mockMvc, expired);
            assertThat(controller.callableCalled).as("the Callable was not called").isFalse();

            MvcResult live = startWithTimeLeft(mockMvc, "/flag/callable");
            assertThat(live.getAsyncResult(TIMEOUT_MILLIS)).isEqualTo("called");
            assertThat(mockMvc.perform(asyncDispatch(live)).andReturn().getResponse().getStatus()).isEqualTo(200);
        });
    }

    @Test
    @DisplayName("EXE-08 the same holds for a WebAsyncTask without an executor of its own")
    void theSameHoldsForAWebAsyncTaskWithoutAnExecutorOfItsOwn() {
        runner.withUserConfiguration(BlockableMvcExecutor.class).run(context -> {
            MockMvc mockMvc = mockMvc(context);
            FlagController controller = context.getBean(FlagController.class);

            MvcResult expired = startAfterExpiry(mockMvc, context.getBean(BlockableMvcExecutor.class).thread, "/flag/web-async-task");

            assertRefused(mockMvc, expired);
            assertThat(controller.webAsyncTaskCalled).as("the WebAsyncTask's Callable was not called").isFalse();
        });
    }

    @Test
    @DisplayName("EXE-08 the check is registered last, after the application's own Callable interceptors, which still get preProcess and"
            + " postProcess")
    void theCheckIsRegisteredLastAfterTheApplicationsOwnCallableInterceptors() {
        runner.withUserConfiguration(BlockableMvcExecutor.class, ApplicationCallableInterceptor.class).run(context -> {
            MockMvc mockMvc = mockMvc(context);
            FlagController controller = context.getBean(FlagController.class);
            List<String> calls = context.getBean(ApplicationCallableInterceptor.class).calls;

            MvcResult expired = startAfterExpiry(mockMvc, context.getBean(BlockableMvcExecutor.class).thread, "/flag/callable");
            assertRefused(mockMvc, expired);

            assertThat(controller.callableCalled).as("the library's check refused the Callable").isFalse();
            assertThat(calls).containsExactly("preProcess", "postProcess EXECUTOR");
            assertThat(context.getBeansOfType(WebMvcConfigurer.class).values()).filteredOn(DeadlineCallableInterceptor.Registration.class::isInstance)
                    .singleElement().extracting(configurer -> ((Ordered) configurer).getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
        });
    }

    @Test
    @DisplayName("EXE-08 the check binds and restores nothing")
    void theCheckBindsAndRestoresNothing() {
        runner.withUserConfiguration(BlockableMvcExecutor.class).run(context -> {
            MockMvc mockMvc = mockMvc(context);
            AtomicInteger mvcThreadEntries = new AtomicInteger();
            ScopeListener counting = entered -> {
                if (Thread.currentThread().getName().equals("blockableMvc-1")) {
                    mvcThreadEntries.incrementAndGet();
                }
                return () -> { };
            };
            Object refused;
            ContextRuntime.addScopeListener(counting);
            try {
                MvcResult live = startWithTimeLeft(mockMvc, "/flag/callable");
                assertThat(live.getAsyncResult(TIMEOUT_MILLIS)).isEqualTo("called");
                MvcResult expired = startAfterExpiry(mockMvc, context.getBean(BlockableMvcExecutor.class).thread, "/flag/callable");
                refused = expired.getAsyncResult(TIMEOUT_MILLIS);
            } finally {
                ContextRuntime.removeScopeListener(counting);
            }

            // Two Callables were run or refused on MVC's thread, each in the one scope AttachedAsyncTaskExecutor enters (EXE-09), as
            // without the library's interceptor; the blocking task, submitted to the raw thread, entered none.
            assertThat(mvcThreadEntries).as("scope entries on MVC's thread").hasValue(2);
            assertThat(refused).as("refused").isInstanceOf(DeadlineExceededException.class);
        });
    }
}
