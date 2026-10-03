package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
}
