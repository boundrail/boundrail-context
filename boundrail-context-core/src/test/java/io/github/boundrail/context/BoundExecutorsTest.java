package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BoundExecutorsTest {

    private static final ContextKey<String> TENANT = ContextKey.of("acme.tenant", String.class);
    private static final ContextKey<String> AUDIT = ContextKey.of("acme.audit", String.class);

    /** The application policy: tenant propagates to both in-process boundaries, audit to ATTACHED only. */
    private static final ContextPolicy POLICY = ContextPolicy.builder()
            .key(TENANT, rules -> rules.propagateTo(Boundary.ATTACHED, Boundary.DETACHED))
            .key(AUDIT, rules -> rules.propagateTo(Boundary.ATTACHED))
            .build();

    /** An executor that queues tasks; {@link #runQueued()} runs them later, on the calling thread. */
    private static final class QueueingExecutor implements Executor {

        private final List<Runnable> queued = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            queued.add(task);
        }

        void runQueued() {
            queued.forEach(Runnable::run);
            queued.clear();
        }
    }

    /** What a task saw: the bound tenant and audit values, or "-". */
    private static String seen() {
        return "tenant=" + TENANT.find().orElse("-") + " audit=" + AUDIT.find().orElse("-");
    }

    @Test
    @DisplayName("EXE-02 a task runs in the snapshot captured at submission, filtered for the mode")
    void aTaskRunsInTheSnapshotCapturedAtSubmissionFilteredForTheMode() {
        QueueingExecutor queue = new QueueingExecutor();
        List<String> seen = new CopyOnWriteArrayList<>();

        Context.with(TENANT, "t1").with(AUDIT, "a1").run(() -> {
            BoundExecutors.attached(queue, POLICY).execute(() -> seen.add("attached " + seen()));
            BoundExecutors.detached(queue, POLICY).execute(() -> seen.add("detached " + seen()));
        });
        Context.with(TENANT, "t2").run(queue::runQueued);

        assertThat(seen).containsExactly("attached tenant=t1 audit=a1", "detached tenant=t1 audit=-");
    }

    @Test
    @DisplayName("EXE-04 overloads without a policy filter with BuiltInKeys.defaultPolicy()")
    void overloadsWithoutAPolicyFilterWithTheDefaultPolicy() {
        QueueingExecutor queue = new QueueingExecutor();
        List<String> seen = new CopyOnWriteArrayList<>();

        Context.with(AUDIT, "a1").run(() -> {
            BoundExecutors.detached(queue).execute(() -> seen.add("default policy " + seen()));
            BoundExecutors.detached(queue, POLICY).execute(() -> seen.add("application policy " + seen()));
        });
        queue.runQueued();

        assertThat(seen).containsExactly("default policy tenant=- audit=a1", "application policy tenant=- audit=-");
    }

    @Test
    @DisplayName("EXE-02 the worker thread is left with no context after the task")
    void theWorkerThreadIsLeftWithNoContextAfterTheTask() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            List<String> seen = new CopyOnWriteArrayList<>();
            Context.with(TENANT, "t1").run(() -> BoundExecutors.attached(worker).execute(() -> seen.add("bound task " + seen())));
            Future<Optional<String>> after = worker.submit(() -> Optional.of(Context.capture().isEmpty() ? "empty" : "not empty"));

            assertThat(after.get(5, TimeUnit.SECONDS)).contains("empty");
            assertThat(seen).containsExactly("bound task tenant=t1 audit=-");
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    @DisplayName("CORE-14 BoundExecutors rejects null")
    void boundExecutorsRejectsNull() {
        Executor executor = Runnable::run;

        assertThatNullPointerException().isThrownBy(() -> BoundExecutors.attached((Executor) null));
        assertThatNullPointerException().isThrownBy(() -> BoundExecutors.detached((Executor) null));
        assertThatNullPointerException().isThrownBy(() -> BoundExecutors.attached((Executor) null, POLICY));
        assertThatNullPointerException().isThrownBy(() -> BoundExecutors.detached((Executor) null, POLICY));
        assertThatNullPointerException().isThrownBy(() -> BoundExecutors.attached(executor, null));
        assertThatNullPointerException().isThrownBy(() -> BoundExecutors.detached(executor, null));
    }

    @Test
    @DisplayName("EXE-02 submit, invokeAll and invokeAny run each task in the snapshot captured at submission")
    void submitInvokeAllAndInvokeAnyRunEachTaskInTheSnapshotCapturedAtSubmission() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            ExecutorService bound = BoundExecutors.attached(worker, POLICY);
            ExecutorService boundDetached = BoundExecutors.detached(worker, POLICY);
            List<String> seen = new CopyOnWriteArrayList<>();
            Callable<String> callable = BoundExecutorsTest::seen;

            List<Future<?>> submitted = Context.with(TENANT, "t1").with(AUDIT, "a1").call(() -> {
                bound.execute(() -> {
                    seen.add("execute " + seen());
                });
                Future<?> runnable = bound.submit(() -> {
                    seen.add("submit(Runnable) " + seen());
                });
                Future<String> runnableWithResult = bound.submit(() -> {
                    seen.add("submit(Runnable, result) " + seen());
                }, "result");
                Future<String> submittedCallable = bound.submit(callable);
                bound.invokeAll(List.of(callable, callable)).forEach(future -> seen.add("invokeAll " + result(future)));
                bound.invokeAll(List.of(callable), 5, TimeUnit.SECONDS).forEach(future -> seen.add("invokeAll timed " + result(future)));
                seen.add("invokeAny " + bound.invokeAny(List.of(callable)));
                seen.add("invokeAny timed " + bound.invokeAny(List.of(callable), 5, TimeUnit.SECONDS));
                seen.add("detached submit(Callable) " + boundDetached.submit(callable).get(5, TimeUnit.SECONDS));
                return List.of(runnable, runnableWithResult, submittedCallable);
            });
            for (Future<?> future : submitted) {
                future.get(5, TimeUnit.SECONDS);
            }
            seen.add("submit(Callable) " + submitted.get(2).get());

            assertThat(seen).containsExactlyInAnyOrder(
                    "execute tenant=t1 audit=a1",
                    "submit(Runnable) tenant=t1 audit=a1",
                    "submit(Runnable, result) tenant=t1 audit=a1",
                    "invokeAll tenant=t1 audit=a1",
                    "invokeAll tenant=t1 audit=a1",
                    "invokeAll timed tenant=t1 audit=a1",
                    "invokeAny tenant=t1 audit=a1",
                    "invokeAny timed tenant=t1 audit=a1",
                    "submit(Callable) tenant=t1 audit=a1",
                    "detached submit(Callable) tenant=t1 audit=-");
        } finally {
            worker.shutdownNow();
        }
    }

    private static String result(Future<String> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** An executor service that records each lifecycle call and returns fixed results. */
    private static final class RecordingExecutorService extends AbstractExecutorService {

        final List<String> calls = new ArrayList<>();
        final List<Runnable> pending = List.of(() -> { });

        @Override
        public void shutdown() {
            calls.add("shutdown");
        }

        @Override
        public List<Runnable> shutdownNow() {
            calls.add("shutdownNow");
            return pending;
        }

        @Override
        public boolean isShutdown() {
            calls.add("isShutdown");
            return true;
        }

        @Override
        public boolean isTerminated() {
            calls.add("isTerminated");
            return true;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            calls.add("awaitTermination " + timeout + " " + unit);
            return true;
        }

        @Override
        public void close() {
            calls.add("close");
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    @Test
    @DisplayName("EXE-04 lifecycle methods delegate")
    void lifecycleMethodsDelegate() throws InterruptedException {
        RecordingExecutorService delegate = new RecordingExecutorService();
        ExecutorService bound = BoundExecutors.detached(delegate);

        bound.shutdown();
        List<Runnable> pending = bound.shutdownNow();
        boolean shutDown = bound.isShutdown();
        boolean terminated = bound.isTerminated();
        boolean awaited = bound.awaitTermination(7, TimeUnit.SECONDS);
        bound.close();

        assertThat(delegate.calls).containsExactly("shutdown", "shutdownNow", "isShutdown", "isTerminated", "awaitTermination 7 SECONDS",
                "close");
        assertThat(pending).isSameAs(delegate.pending);
        assertThat(List.of(shutDown, terminated, awaited)).containsOnly(true);
    }

    @Test
    @DisplayName("CORE-14 the ExecutorService overloads reject null")
    void theExecutorServiceOverloadsRejectNull() throws InterruptedException {
        ExecutorService service = Executors.newSingleThreadExecutor();
        try {
            assertThatNullPointerException().isThrownBy(() -> BoundExecutors.attached((ExecutorService) null));
            assertThatNullPointerException().isThrownBy(() -> BoundExecutors.detached((ExecutorService) null));
            assertThatNullPointerException().isThrownBy(() -> BoundExecutors.attached((ExecutorService) null, POLICY));
            assertThatNullPointerException().isThrownBy(() -> BoundExecutors.detached((ExecutorService) null, POLICY));
            assertThatNullPointerException().isThrownBy(() -> BoundExecutors.attached(service, null));
            assertThatNullPointerException().isThrownBy(() -> BoundExecutors.detached(service, null));

            ExecutorService bound = BoundExecutors.attached(service);
            List<Callable<String>> withNull = Arrays.asList(() -> "a", null);
            assertThatNullPointerException().isThrownBy(() -> bound.invokeAll(withNull));
            assertThatNullPointerException().isThrownBy(() -> bound.invokeAll(withNull, 5, TimeUnit.SECONDS));
        } finally {
            service.shutdownNow();
        }
    }

    /** A binding of a deadline that has already expired. */
    private static ContextBinding expired() {
        return Context.with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ZERO));
    }

    /** Asserts that the future fails with {@code ExecutionException} caused by {@code DeadlineExceededException} at {@code EXECUTOR}. */
    private static void assertRefused(Future<?> future, String description) {
        Throwable thrown = catchThrowable(() -> future.get(5, TimeUnit.SECONDS));
        assertThat(thrown).as(description).isInstanceOf(ExecutionException.class);
        assertThat(thrown.getCause()).as(description).isInstanceOf(DeadlineExceededException.class);
        assertThat(((DeadlineExceededException) thrown.getCause()).stage()).as(description).isEqualTo(DeadlineExceededException.Stage.EXECUTOR);
    }

    @Test
    @DisplayName("EXE-08 a task submitted to an attached executor service after its deadline has expired is not run, and its future fails with"
            + " DeadlineExceededException at EXECUTOR")
    void aTaskSubmittedAfterItsDeadlineHasExpiredIsNotRun() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            ExecutorService attached = BoundExecutors.attached(pool, POLICY);
            AtomicBoolean callableRan = new AtomicBoolean();
            AtomicBoolean runnableRan = new AtomicBoolean();
            AtomicBoolean runnableWithResultRan = new AtomicBoolean();

            List<Future<?>> refused = expired().call(() -> List.of(
                    attached.submit(() -> callableRan.getAndSet(true)),
                    attached.submit(() -> runnableRan.set(true)),
                    attached.submit(() -> runnableWithResultRan.set(true), "result")));

            assertRefused(refused.get(0), "submit(Callable)");
            assertRefused(refused.get(1), "submit(Runnable)");
            assertRefused(refused.get(2), "submit(Runnable, result)");
            assertThat(List.of(callableRan.get(), runnableRan.get(), runnableWithResultRan.get())).as("no task ran").containsOnly(false);

            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean lateRan = new AtomicBoolean();
            pool.submit(() -> release.await(5, TimeUnit.SECONDS));
            Deadline shortDeadline = Deadline.after(Duration.ofMillis(50));
            Future<?> late = Context.with(BuiltInKeys.DEADLINE, shortDeadline).call(() -> attached.submit(() -> lateRan.set(true)));
            while (!shortDeadline.isExpired()) {
                Thread.sleep(5);
            }
            release.countDown();

            assertRefused(late, "submitted before expiry, started after it");
            assertThat(lateRan).as("the late task did not run").isFalse();

            AtomicBoolean liveRan = new AtomicBoolean();
            Context.with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ofSeconds(10))).call(() -> attached.submit(() -> liveRan.set(true)))
                    .get(5, TimeUnit.SECONDS);
            assertThat(liveRan).as("a task with time left runs").isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("EXE-08 invokeAll and invokeAny refuse expired tasks")
    void invokeAllAndInvokeAnyRefuseExpiredTasks() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            ExecutorService attached = BoundExecutors.attached(pool, POLICY);
            AtomicInteger ran = new AtomicInteger();
            List<Callable<String>> tasks = List.of(() -> "a" + ran.incrementAndGet(), () -> "b" + ran.incrementAndGet());

            List<Future<String>> all = expired().call(() -> attached.invokeAll(tasks));
            Throwable any = expired().call(() -> catchThrowable(() -> attached.invokeAny(tasks)));

            assertThat(all).hasSize(2);
            all.forEach(future -> assertRefused(future, "invokeAll"));
            assertThat(any).as("invokeAny").isInstanceOf(ExecutionException.class);
            assertThat(any.getCause()).isInstanceOf(DeadlineExceededException.class);
            assertThat(((DeadlineExceededException) any.getCause()).stage()).isEqualTo(DeadlineExceededException.Stage.EXECUTOR);
            assertThat(ran).as("no task ran").hasValue(0);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("EXE-08 a detached executor service runs the task, because its snapshot holds no deadline")
    void aDetachedExecutorServiceRunsTheTask() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Deadline>> detached = expired().call(() -> BoundExecutors.detached(pool, POLICY).submit(Deadlines::current));
            Future<Optional<Deadline>> attached = expired().call(() -> BoundExecutors.attached(pool, POLICY).submit(Deadlines::current));

            assertThat(detached.get(5, TimeUnit.SECONDS)).as("detached: runs, and sees no deadline").isEmpty();
            assertRefused(attached, "attached");
        } finally {
            pool.shutdownNow();
        }
    }
}
