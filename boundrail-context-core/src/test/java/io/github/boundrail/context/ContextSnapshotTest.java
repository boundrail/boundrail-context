package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContextSnapshotTest {

    private static final ContextKey<String> TENANT = ContextKey.of("acme.tenant", String.class);
    private static final ContextKey<String> REGION = ContextKey.of("acme.region", String.class);
    private static final ContextKey<String> USER = ContextKey.of("acme.user", String.class);

    /** The bound value of each test key in the current snapshot, or "unbound". */
    private static List<String> boundValues() {
        return Stream.of(TENANT, REGION, USER)
                .map(key -> key.isBound() ? key.name() + "=" + key.get() : key.name() + " unbound")
                .toList();
    }

    @Test
    @DisplayName("CORE-09 capture returns the current snapshot without copying")
    void captureReturnsTheCurrentSnapshotWithoutCopying() {
        ContextSnapshot snapshot = Context.with(TENANT, "t1").call(Context::capture);
        AtomicReference<ContextSnapshot> captured = new AtomicReference<>();

        snapshot.run(() -> captured.set(Context.capture()));

        assertThat(captured.get()).isSameAs(snapshot);
    }

    @Test
    @DisplayName("CORE-09 capture outside any scope returns EMPTY")
    void captureOutsideAnyScopeReturnsEmpty() {
        assertThat(Context.capture()).isSameAs(ContextSnapshot.EMPTY);

        Context.with(TENANT, "t1").run(() -> { });

        assertThat(Context.capture()).isSameAs(ContextSnapshot.EMPTY);
    }

    @Test
    @DisplayName("CORE-10 snapshot run replaces the caller's snapshot and restores it")
    void snapshotRunReplacesTheCallersSnapshotAndRestoresIt() {
        ContextSnapshot snapshot = Context.with(TENANT, "t1").call(Context::capture);
        IllegalStateException failure = new IllegalStateException("boom");
        AtomicReference<ContextSnapshot> caller = new AtomicReference<>();
        List<ContextSnapshot> afterRun = new ArrayList<>();
        List<List<String>> seen = new ArrayList<>();

        Context.with(TENANT, "t0").with(REGION, "us").run(() -> {
            caller.set(Context.capture());
            snapshot.run(() -> seen.add(boundValues()));
            seen.add(boundValues());
            afterRun.add(Context.capture());
            catchThrowableOfType(IllegalStateException.class, () -> snapshot.run(() -> {
                throw failure;
            }));
            seen.add(boundValues());
            afterRun.add(Context.capture());
        });

        assertThat(seen).containsExactly(
                List.of("acme.tenant=t1", "acme.region unbound", "acme.user unbound"),
                List.of("acme.tenant=t0", "acme.region=us", "acme.user unbound"),
                List.of("acme.tenant=t0", "acme.region=us", "acme.user unbound"));
        assertThat(afterRun).hasSize(2).allSatisfy(current -> assertThat(current).isSameAs(caller.get()));
    }

    @Test
    @DisplayName("CORE-10 snapshot call returns the result and rethrows checked exceptions")
    void snapshotCallReturnsTheResultAndRethrowsCheckedExceptions() {
        ContextSnapshot snapshot = Context.with(TENANT, "t1").call(Context::capture);
        IOException checkedFailure = new IOException("boom");
        List<String> results = new ArrayList<>();
        AtomicReference<IOException> checked = new AtomicReference<>();

        Context.with(TENANT, "t0").run(() -> {
            results.add(snapshot.call(TENANT::get));
            checked.set(catchThrowableOfType(IOException.class, () -> snapshot.<Void, IOException>call(() -> {
                throw checkedFailure;
            })));
            results.add(TENANT.get());
        });

        assertThat(results).containsExactly("t1", "t0");
        assertThat(checked.get()).isSameAs(checkedFailure);
    }

    @Test
    @DisplayName("CORE-11 one snapshot runs concurrently on many threads")
    void oneSnapshotRunsConcurrentlyOnManyThreads() throws Exception {
        int threads = 100;
        ContextSnapshot snapshot = Context.with(TENANT, "t1").with(REGION, "eu").call(Context::capture);
        CountDownLatch allBound = new CountDownLatch(threads);
        List<Future<List<String>>> seen = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                String user = "u" + i;
                seen.add(pool.submit(() -> snapshot.call(() -> Context.with(USER, user).call(() -> {
                    // every thread holds its own USER binding while the others read theirs
                    allBound.countDown();
                    if (!allBound.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("not all threads entered their scope");
                    }
                    return boundValues();
                }))));
            }
        }

        for (int i = 0; i < threads; i++) {
            assertThat(seen.get(i).get()).containsExactly("acme.tenant=t1", "acme.region=eu", "acme.user=u" + i);
        }
        assertThat(snapshot.call(ContextSnapshotTest::boundValues))
                .containsExactly("acme.tenant=t1", "acme.region=eu", "acme.user unbound");
    }

    @Test
    @DisplayName("CORE-03 snapshot run and call reject a null operation with NullPointerException and leave the current snapshot unchanged")
    void snapshotRunAndCallRejectANullOperationAndLeaveTheCurrentSnapshotUnchanged() {
        ContextSnapshot snapshot = Context.with(TENANT, "t1").call(Context::capture);

        assertThatNullPointerException().isThrownBy(() -> snapshot.run(null));
        assertThat(Context.capture()).isSameAs(ContextSnapshot.EMPTY);

        assertThatNullPointerException().isThrownBy(() -> snapshot.call(null));
        assertThat(Context.capture()).isSameAs(ContextSnapshot.EMPTY);
    }

    @Test
    @DisplayName("CORE-10 wrap(Runnable) runs later on another thread with the snapshot")
    void wrapRunnableRunsLaterOnAnotherThreadWithTheSnapshot() throws Exception {
        ContextSnapshot snapshot = Context.with(TENANT, "t1").call(Context::capture);
        List<List<String>> seen = new ArrayList<>();
        AtomicReference<Thread> runner = new AtomicReference<>();
        List<ContextSnapshot> executorSnapshots = new ArrayList<>();

        Runnable wrapped = snapshot.wrap(() -> {
            runner.set(Thread.currentThread());
            seen.add(boundValues());
        });

        assertThat(seen).isEmpty();
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            pool.submit(() -> Context.with(TENANT, "t9").with(REGION, "ap").run(() -> {
                executorSnapshots.add(Context.capture());
                wrapped.run();
                seen.add(boundValues());
                executorSnapshots.add(Context.capture());
            })).get();
        }

        assertThat(runner.get()).isNotNull().isNotSameAs(Thread.currentThread());
        assertThat(seen).containsExactly(
                List.of("acme.tenant=t1", "acme.region unbound", "acme.user unbound"),
                List.of("acme.tenant=t9", "acme.region=ap", "acme.user unbound"));
        assertThat(executorSnapshots).hasSize(2);
        assertThat(executorSnapshots.get(1)).isSameAs(executorSnapshots.get(0));
    }

    @Test
    @DisplayName("CORE-10 wrap(Callable) runs later on another thread with the snapshot and returns its result")
    void wrapCallableRunsLaterOnAnotherThreadWithTheSnapshotAndReturnsItsResult() throws Exception {
        ContextSnapshot snapshot = Context.with(TENANT, "t1").call(Context::capture);
        List<List<String>> seen = new ArrayList<>();
        AtomicReference<Thread> runner = new AtomicReference<>();
        List<ContextSnapshot> executorSnapshots = new ArrayList<>();
        Callable<String> operation = () -> {
            runner.set(Thread.currentThread());
            seen.add(boundValues());
            return "result for " + TENANT.get();
        };

        Callable<String> wrapped = snapshot.wrap(operation);

        assertThat(seen).isEmpty();
        String result;
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            result = pool.submit(() -> Context.with(TENANT, "t9").with(REGION, "ap").call(() -> {
                executorSnapshots.add(Context.capture());
                String value = wrapped.call();
                seen.add(boundValues());
                executorSnapshots.add(Context.capture());
                return value;
            })).get();
        }

        assertThat(result).isEqualTo("result for t1");
        assertThat(runner.get()).isNotNull().isNotSameAs(Thread.currentThread());
        assertThat(seen).containsExactly(
                List.of("acme.tenant=t1", "acme.region unbound", "acme.user unbound"),
                List.of("acme.tenant=t9", "acme.region=ap", "acme.user unbound"));
        assertThat(executorSnapshots).hasSize(2);
        assertThat(executorSnapshots.get(1)).isSameAs(executorSnapshots.get(0));
    }

    @Test
    @DisplayName("CORE-03 wrap rejects a null operation with NullPointerException at the wrap call")
    void wrapRejectsANullOperationAtTheWrapCall() {
        ContextSnapshot snapshot = Context.with(TENANT, "t1").call(Context::capture);

        assertThatNullPointerException().isThrownBy(() -> snapshot.wrap((Runnable) null));
        assertThatNullPointerException().isThrownBy(() -> snapshot.wrap((Callable<String>) null));
    }
}
