package io.github.boundrail.context.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextBinding;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextRuntime;
import io.github.boundrail.context.ContextSnapshot;
import io.github.boundrail.context.Deadline;
import io.github.boundrail.context.MissingContextException;
import io.github.boundrail.context.ScopeListener;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runtime conformance suite (BLD-06, RT-06): the same scenarios must pass on the ThreadLocal and ScopedValue backends, so this suite
 * runs in all three integration test tasks. Scenarios come from specs/002-core-runtime (RC-1 – RC-8) and specs/004-async-diagnostics
 * (RC-9); each body is written test-first when its feature enables it (BLD-10).
 */
class RuntimeConformanceIT {

    /** The bound value of each key in the current snapshot, or "unbound"; defaults do not count. */
    private static List<String> boundValues(ContextKey<?>... keys) {
        return Stream.of(keys).map(key -> key.isBound() ? key.name() + "=" + key.get() : key.name() + " unbound").toList();
    }

    /** What a pooled task observed: the thread it ran on, the keys bound there and the captured snapshot. */
    private record Observed(Thread thread, List<String> boundValues, ContextSnapshot captured) {
    }

    /** The snapshot captured just before a nested scope and just after it exits. */
    private record Restored(ContextSnapshot before, ContextSnapshot after) {
    }

    @Test
    @DisplayName("RC-1 Active backend matches the JDK and the override")
    void rc1ActiveBackendMatchesTheJDKAndTheOverride() {
        // RC-1: The active backend is `SCOPED_VALUE` under `integrationTestJdk25`, and `THREAD_LOCAL` under `integrationTestJdk21` and
        // `integrationTestJdk25ThreadLocal`. The runtime feature version (`Runtime.version().feature()`) is 21 under
        // `integrationTestJdk21` and 25 under the other two tasks, so the override is proven on JDK 25. The task is read from the system
        // property `boundrail.it.task` (BLD-06); RC-1 fails, and is not skipped, when that property is missing or names a task other than
        // these three. *(RT-02, RT-04, RT-05, BLD-06)*
        String task = System.getProperty("boundrail.it.task");
        record Expected(int feature, ContextRuntime.Backend backend) {
        }
        Expected expected = switch (task) {
            case "integrationTestJdk21" -> new Expected(21, ContextRuntime.Backend.THREAD_LOCAL);
            case "integrationTestJdk25" -> new Expected(25, ContextRuntime.Backend.SCOPED_VALUE);
            case "integrationTestJdk25ThreadLocal" -> new Expected(25, ContextRuntime.Backend.THREAD_LOCAL);
            case null -> throw new AssertionError("system property boundrail.it.task is missing");
            default -> throw new AssertionError("system property boundrail.it.task names an unknown task: " + task);
        };

        assertThat(new Expected(Runtime.version().feature(), ContextRuntime.backend())).as("JDK and backend under %s", task)
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("RC-2 Nested bindings restore the exact previous snapshot")
    void rc2NestedBindingsRestoreTheExactPreviousSnapshot() {
        // RC-2: Nested bindings rebind and remove keys; each scope exit restores the exact previous snapshot, including when the operation
        // throws a runtime exception or a checked exception. *(CORE-07, CORE-08)*
        ContextKey<String> tenant = ContextKey.of("rc.tenant", String.class);
        ContextKey<String> region = ContextKey.of("rc.region", String.class);
        ContextKey<String> user = ContextKey.of("rc.user", String.class);
        IllegalStateException runtimeFailure = new IllegalStateException("boom");
        IOException checkedFailure = new IOException("boom");
        List<Throwable> thrown = new ArrayList<>();
        List<List<String>> seen = new ArrayList<>();
        List<Restored> restored = new ArrayList<>();

        Context.with(tenant, "t1").with(region, "eu").run(() -> {
            ContextSnapshot outer = Context.capture();
            Context.with(tenant, "t2").without(region).with(user, "alice").run(() -> {
                seen.add(boundValues(tenant, region, user));
                ContextSnapshot middle = Context.capture();
                Context.without(tenant).with(region, "us").run(() -> seen.add(boundValues(tenant, region, user)));
                restored.add(new Restored(middle, Context.capture()));
                seen.add(boundValues(tenant, region, user));
            });
            restored.add(new Restored(outer, Context.capture()));
            seen.add(boundValues(tenant, region, user));
            thrown.add(catchThrowableOfType(IllegalStateException.class,
                    () -> Context.with(tenant, "t3").without(region).with(user, "bob").run(() -> {
                        throw runtimeFailure;
                    })));
            restored.add(new Restored(outer, Context.capture()));
            seen.add(boundValues(tenant, region, user));
            thrown.add(catchThrowableOfType(IOException.class,
                    () -> Context.without(tenant).with(region, "us").with(user, "carol").<Void, IOException>call(() -> {
                        throw checkedFailure;
                    })));
            restored.add(new Restored(outer, Context.capture()));
            seen.add(boundValues(tenant, region, user));
        });
        seen.add(boundValues(tenant, region, user));

        assertThat(thrown).containsExactly(runtimeFailure, checkedFailure);
        assertThat(restored).hasSize(4).allSatisfy(scope -> assertThat(scope.after()).isSameAs(scope.before()));
        assertThat(seen).containsExactly(
                List.of("rc.tenant=t2", "rc.region unbound", "rc.user=alice"),
                List.of("rc.tenant unbound", "rc.region=us", "rc.user=alice"),
                List.of("rc.tenant=t2", "rc.region unbound", "rc.user=alice"),
                List.of("rc.tenant=t1", "rc.region=eu", "rc.user unbound"),
                List.of("rc.tenant=t1", "rc.region=eu", "rc.user unbound"),
                List.of("rc.tenant=t1", "rc.region=eu", "rc.user unbound"),
                List.of("rc.tenant unbound", "rc.region unbound", "rc.user unbound"));
    }

    @Test
    @DisplayName("RC-3 Snapshot run replaces the caller's snapshot")
    void rc3SnapshotRunReplacesTheCallersSnapshot() {
        // RC-3: `snapshot.run` replaces the caller's snapshot rather than merging with it, and restores it afterwards. *(CORE-10)*
        ContextKey<String> tenant = ContextKey.of("rc.tenant", String.class);
        ContextKey<String> region = ContextKey.of("rc.region", String.class);
        ContextSnapshot snapshot = Context.with(tenant, "t1").call(Context::capture);
        List<List<String>> seen = new ArrayList<>();
        List<Restored> restored = new ArrayList<>();

        Context.with(tenant, "t0").with(region, "eu").run(() -> {
            ContextSnapshot caller = Context.capture();
            snapshot.run(() -> seen.add(boundValues(tenant, region)));
            restored.add(new Restored(caller, Context.capture()));
            seen.add(boundValues(tenant, region));
        });

        assertThat(seen).containsExactly(
                List.of("rc.tenant=t1", "rc.region unbound"),
                List.of("rc.tenant=t0", "rc.region=eu"));
        assertThat(restored).singleElement().satisfies(scope -> assertThat(scope.after()).isSameAs(scope.before()));
    }

    @Test
    @DisplayName("RC-4 One snapshot is shared safely across virtual threads")
    void rc4OneSnapshotIsSharedSafelyAcrossVirtualThreads() throws Exception {
        // RC-4: One snapshot run concurrently on 1,000 virtual threads makes the same values visible on every thread, and bindings made
        // inside those threads never become visible to the others. *(CORE-11)*
        int threads = 1_000;
        ContextKey<String> tenant = ContextKey.of("rc.tenant", String.class);
        ContextKey<String> region = ContextKey.of("rc.region", String.class);
        ContextKey<String> user = ContextKey.of("rc.user", String.class);
        ContextSnapshot snapshot = Context.with(tenant, "t1").with(region, "eu").call(Context::capture);
        CountDownLatch allBound = new CountDownLatch(threads);
        List<Future<List<String>>> seen = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                String name = "u" + i;
                seen.add(pool.submit(() -> snapshot.call(() -> Context.with(user, name).call(() -> {
                    // every thread holds its own user binding while the others read theirs
                    allBound.countDown();
                    if (!allBound.await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("not all threads entered their scope");
                    }
                    return boundValues(tenant, region, user);
                }))));
            }
        }

        for (int i = 0; i < threads; i++) {
            assertThat(seen.get(i).get()).containsExactly("rc.tenant=t1", "rc.region=eu", "rc.user=u" + i);
        }
        assertThat(snapshot.call(() -> boundValues(tenant, region, user)))
                .containsExactly("rc.tenant=t1", "rc.region=eu", "rc.user unbound");
    }

    @Test
    @DisplayName("RC-5 Pooled threads carry no residue")
    void rc5PooledThreadsCarryNoResidue() throws Exception {
        // RC-5: After a scope exits on a pooled platform thread, the next task on that thread observes `EMPTY`. *(RT-03)*
        ContextKey<String> tenant = ContextKey.of("rc.tenant", String.class);
        ContextKey<String> region = ContextKey.of("rc.region", String.class);
        Thread normalThread;
        Observed afterNormalExit;
        Thread throwingThread;
        Observed afterExceptionalExit;

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            normalThread = pool.submit(() -> {
                Context.with(tenant, "t1").run(() -> { });
                return Thread.currentThread();
            }).get();
            afterNormalExit = pool.submit(
                    () -> new Observed(Thread.currentThread(), boundValues(tenant, region), Context.capture())).get();
            throwingThread = pool.submit(() -> {
                try {
                    Context.with(tenant, "t2").with(region, "eu").run(() -> {
                        throw new IllegalStateException("boom");
                    });
                } catch (IllegalStateException expected) {
                    // the scope exited exceptionally; the worker thread survives
                }
                return Thread.currentThread();
            }).get();
            afterExceptionalExit = pool.submit(
                    () -> new Observed(Thread.currentThread(), boundValues(tenant, region), Context.capture())).get();
        }

        assertThat(afterNormalExit.thread()).isSameAs(normalThread);
        assertThat(afterNormalExit.boundValues()).containsExactly("rc.tenant unbound", "rc.region unbound");
        assertThat(afterNormalExit.captured()).isSameAs(ContextSnapshot.EMPTY);
        assertThat(afterExceptionalExit.thread()).isSameAs(throwingThread);
        assertThat(afterExceptionalExit.boundValues()).containsExactly("rc.tenant unbound", "rc.region unbound");
        assertThat(afterExceptionalExit.captured()).isSameAs(ContextSnapshot.EMPTY);
    }

    @Test
    @DisplayName("RC-6 Missing keys and defaults")
    void rc6MissingKeysAndDefaults() {
        // RC-6: `get()` on an unbound key without a default throws `MissingContextException` naming the key; with a default, it returns
        // the default and `isBound()` is false. *(CORE-05)*
        ContextKey<String> withoutDefault = ContextKey.of("rc.tenant", String.class);
        ContextKey<String> withDefault = ContextKey.of("rc.region", String.class, "eu");

        MissingContextException missing = catchThrowableOfType(MissingContextException.class, withoutDefault::get);
        assertThat(missing.key()).isSameAs(withoutDefault);
        assertThat(missing).hasMessageContaining("rc.tenant");

        assertThat(withDefault.get()).isEqualTo("eu");
        assertThat(withDefault.isBound()).isFalse();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    @DisplayName("RC-7 Null and wrong-type values are rejected when binding")
    void rc7NullAndWrongTypeValuesAreRejectedWhenBinding() {
        // RC-7: Binding `null` throws `NullPointerException`, and binding a value of the wrong type throws `ClassCastException` at the
        // binding call. *(CORE-03, CORE-04)*
        ContextKey<String> tenant = ContextKey.of("rc.tenant", String.class);
        ContextKey raw = tenant;
        ContextBinding binding = Context.with(ContextKey.of("rc.region", String.class), "eu");

        assertThatNullPointerException().isThrownBy(() -> Context.with(tenant, null));
        assertThatNullPointerException().isThrownBy(() -> binding.with(tenant, null));
        assertThatExceptionOfType(ClassCastException.class).isThrownBy(() -> Context.with(raw, 42));
        assertThatExceptionOfType(ClassCastException.class).isThrownBy(() -> binding.with(raw, 42));
    }

    @Test
    @DisplayName("RC-8 Deadlines can be shortened but never extended or removed")
    void rc8DeadlinesCanBeShortenedButNeverExtendedOrRemoved() {
        // RC-8: Within a scope whose deadline has 100ms remaining, binding a deadline of 10s leaves at most 100ms remaining, binding one
        // of 10ms shortens it to at most 10ms, and `Context.without(DEADLINE)` throws `IllegalArgumentException`. *(DL-10, DL-11)*
        List<Duration> remaining = new ArrayList<>();
        List<Throwable> rejected = new ArrayList<>();

        Context.with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ofMillis(100))).run(() -> {
            Context.with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ofSeconds(10)))
                    .run(() -> remaining.add(BuiltInKeys.DEADLINE.get().remaining()));
            Context.with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ofMillis(10)))
                    .run(() -> remaining.add(BuiltInKeys.DEADLINE.get().remaining()));
            try {
                Context.without(BuiltInKeys.DEADLINE);
            } catch (IllegalArgumentException expected) {
                rejected.add(expected);
            }
        });

        assertThat(remaining).hasSize(2);
        assertThat(remaining.get(0)).isLessThanOrEqualTo(Duration.ofMillis(100));
        assertThat(remaining.get(1)).isLessThanOrEqualTo(Duration.ofMillis(10));
        assertThat(rejected).hasSize(1);
    }

    @Test
    @DisplayName("RC-9 Scope listeners")
    void rc9ScopeListeners() {
        // RC-9: Two registered listeners are entered in registration order and restored in reverse order for every nested scope, including
        // when the operation throws. A listener that throws does not change the operation's result. *(RT-07)*
        ContextKey<String> tenant = ContextKey.of("rc.tenant", String.class);
        ContextSnapshot t3 = Context.with(tenant, "t3").call(Context::capture);
        List<String> calls = new ArrayList<>();
        // Listeners are JVM-wide and the shared topology may enter scopes on other threads, so record only this test's scopes.
        Thread testThread = Thread.currentThread();
        Function<String, ScopeListener> recording = name -> entered -> {
            if (Thread.currentThread() != testThread) {
                return () -> { };
            }
            calls.add("enter " + name + " " + tenant.find().orElse("-"));
            return () -> calls.add("restore " + name + " " + tenant.find().orElse("-"));
        };
        ScopeListener a = recording.apply("A");
        ScopeListener b = recording.apply("B");
        ScopeListener throwing = entered -> {
            if (Thread.currentThread() != testThread) {
                return () -> { };
            }
            throw new IllegalStateException("RC-9 listener failure");
        };

        ContextRuntime.addScopeListener(a);
        ContextRuntime.addScopeListener(b);
        try {
            Context.with(tenant, "t1").run(() -> Context.with(tenant, "t2").run(() -> { }));
            assertThat(calls).as("nested bindings").containsExactly(
                    "enter A t1", "enter B t1", "enter A t2", "enter B t2", "restore B t2", "restore A t2", "restore B t1", "restore A t1");

            calls.clear();
            IOException thrown = new IOException("RC-9 operation failure");
            IOException caught = catchThrowableOfType(IOException.class, () -> t3.call(() -> {
                throw thrown;
            }));
            assertThat(caught).isSameAs(thrown);
            assertThat(calls).as("snapshot.call that throws").containsExactly("enter A t3", "enter B t3", "restore B t3", "restore A t3");

            calls.clear();
            ContextRuntime.addScopeListener(throwing);
            String result = Context.with(tenant, "t4").call(tenant::get);
            assertThat(result).isEqualTo("t4");
            assertThat(calls).as("with a throwing listener").containsExactly("enter A t4", "enter B t4", "restore B t4", "restore A t4");
        } finally {
            ContextRuntime.removeScopeListener(throwing);
            ContextRuntime.removeScopeListener(b);
            ContextRuntime.removeScopeListener(a);
        }
    }
}
