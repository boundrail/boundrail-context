package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContextBindingTest {

    private static final ContextKey<String> TENANT = ContextKey.of("acme.tenant", String.class);
    private static final ContextKey<String> REGION = ContextKey.of("acme.region", String.class, "eu");
    private static final ContextKey<String> USER = ContextKey.of("acme.user", String.class);

    /** The bound value of each test key in the current snapshot, or "unbound"; defaults do not count. */
    private static List<String> boundValues() {
        return Stream.of(TENANT, REGION, USER)
                .map(key -> key.isBound() ? key.name() + "=" + key.get() : key.name() + " unbound")
                .toList();
    }

    @Test
    @DisplayName("CORE-05 find returns the bound value, else the default, else empty")
    void findReturnsTheBoundValueElseTheDefaultElseEmpty() {
        assertThat(TENANT.find()).isEmpty();
        assertThat(REGION.find()).contains("eu");

        List<Optional<String>> inside = new ArrayList<>();
        Context.with(TENANT, "t1").with(REGION, "us").run(() -> {
            inside.add(TENANT.find());
            inside.add(REGION.find());
        });

        assertThat(inside).containsExactly(Optional.of("t1"), Optional.of("us"));
    }

    @Test
    @DisplayName("CORE-05 get throws MissingContextException naming the key")
    void getThrowsMissingContextExceptionNamingTheKey() {
        MissingContextException missing = catchThrowableOfType(MissingContextException.class, TENANT::get);

        assertThat(missing.key()).isSameAs(TENANT);
        assertThat(missing).hasMessageContaining("acme.tenant");
    }

    @Test
    @DisplayName("CORE-05 get returns the bound value, else the default")
    void getReturnsTheBoundValueElseTheDefault() {
        assertThat(REGION.get()).isEqualTo("eu");

        List<String> inside = new ArrayList<>();
        Context.with(TENANT, "t1").run(() -> inside.add(TENANT.get()));

        assertThat(inside).containsExactly("t1");
    }

    @Test
    @DisplayName("CORE-05 isBound ignores defaults")
    void isBoundIgnoresDefaults() {
        assertThat(REGION.isBound()).isFalse();

        List<Boolean> inside = new ArrayList<>();
        Context.with(REGION, "us").run(() -> inside.add(REGION.isBound()));

        assertThat(inside).containsExactly(true);
    }

    @Test
    @DisplayName("CORE-07 run binds for the scope and restores afterwards")
    void runBindsForTheScopeAndRestoresAfterwards() {
        List<Optional<String>> inside = new ArrayList<>();
        List<Optional<String>> nested = new ArrayList<>();

        Context.with(TENANT, "t1").run(() -> {
            inside.add(TENANT.find());
            Context.with(REGION, "us").run(() -> {
                nested.add(TENANT.find());
                nested.add(REGION.find());
            });
        });

        assertThat(inside).containsExactly(Optional.of("t1"));
        assertThat(nested).containsExactly(Optional.of("t1"), Optional.of("us"));
        assertThat(TENANT.isBound()).isFalse();
    }

    @Test
    @DisplayName("CORE-02 keys with the same name are different keys")
    void keysWithTheSameNameAreDifferentKeys() {
        ContextKey<String> first = ContextKey.of("acme.shared", String.class);
        ContextKey<String> second = ContextKey.of("acme.shared", String.class);
        List<String> inside = new ArrayList<>();

        Context.with(first, "one").with(second, "two").run(() -> {
            inside.add(first.get());
            inside.add(second.get());
        });

        assertThat(inside).containsExactly("one", "two");
    }

    @Test
    @DisplayName("RT-01 each thread has its own current snapshot")
    void eachThreadHasItsOwnCurrentSnapshot() throws InterruptedException {
        AtomicReference<Optional<String>> seenByOtherThread = new AtomicReference<>();
        AtomicReference<Optional<String>> seenAfterOtherThreadBound = new AtomicReference<>();

        Context.with(TENANT, "main").run(() -> {
            Thread other = new Thread(() -> {
                seenByOtherThread.set(TENANT.find());
                Context.with(TENANT, "other").run(() -> { });
            });
            other.start();
            try {
                other.join();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            seenAfterOtherThreadBound.set(TENANT.find());
        });

        assertThat(seenByOtherThread.get()).isEmpty();
        assertThat(seenAfterOtherThreadBound.get()).contains("main");
    }

    @Test
    @DisplayName("CORE-03 with rejects a null key with NullPointerException at the with call")
    void withRejectsANullKeyAtTheWithCall() {
        ContextBinding binding = Context.with(TENANT, "t1");

        assertThatNullPointerException().isThrownBy(() -> Context.with((ContextKey<String>) null, "v"));
        assertThatNullPointerException().isThrownBy(() -> binding.with((ContextKey<String>) null, "v"));
    }

    @Test
    @DisplayName("CORE-03 run rejects a null operation with NullPointerException and leaves the current snapshot unchanged")
    void runRejectsANullOperationAndLeavesTheCurrentSnapshotUnchanged() {
        assertThatNullPointerException().isThrownBy(() -> Context.with(TENANT, "t1").run(null));

        assertThat(TENANT.isBound()).isFalse();
        assertThat(Context.capture()).isSameAs(ContextSnapshot.EMPTY);
    }

    @Test
    @DisplayName("CORE-07 without removes a key for the scope")
    void withoutRemovesAKeyForTheScope() {
        List<List<String>> seen = new ArrayList<>();

        Context.with(TENANT, "t1").with(REGION, "us").run(() -> {
            Context.without(TENANT).run(() -> seen.add(boundValues()));
            Context.with(USER, "alice").without(REGION).run(() -> seen.add(boundValues()));
            seen.add(boundValues());
        });

        assertThat(seen).containsExactly(
                List.of("acme.tenant unbound", "acme.region=us", "acme.user unbound"),
                List.of("acme.tenant=t1", "acme.region unbound", "acme.user=alice"),
                List.of("acme.tenant=t1", "acme.region=us", "acme.user unbound"));
    }

    @Test
    @DisplayName("CORE-07 the last change for a key wins within one binding")
    void theLastChangeForAKeyWinsWithinOneBinding() {
        List<Optional<String>> seen = new ArrayList<>();

        Context.with(TENANT, "0").run(() -> {
            Context.with(TENANT, "1").without(TENANT).run(() -> seen.add(TENANT.find()));
            Context.without(TENANT).with(TENANT, "2").run(() -> seen.add(TENANT.find()));
            Context.with(TENANT, "1").with(TENANT, "2").run(() -> seen.add(TENANT.find()));
        });

        assertThat(seen).containsExactly(Optional.empty(), Optional.of("2"), Optional.of("2"));
    }

    @Test
    @DisplayName("CORE-07 nested bindings restore the exact previous snapshot")
    void nestedBindingsRestoreTheExactPreviousSnapshot() {
        List<List<String>> seen = new ArrayList<>();
        AtomicReference<ContextSnapshot> beforeMiddle = new AtomicReference<>();
        AtomicReference<ContextSnapshot> afterMiddle = new AtomicReference<>();
        AtomicReference<ContextSnapshot> beforeInner = new AtomicReference<>();
        AtomicReference<ContextSnapshot> afterInner = new AtomicReference<>();

        Context.with(TENANT, "t1").with(REGION, "us").run(() -> {
            beforeMiddle.set(Context.capture());
            Context.with(TENANT, "t2").without(REGION).with(USER, "alice").run(() -> {
                seen.add(boundValues());
                beforeInner.set(Context.capture());
                Context.without(TENANT).with(REGION, "ap").run(() -> seen.add(boundValues()));
                afterInner.set(Context.capture());
                seen.add(boundValues());
            });
            afterMiddle.set(Context.capture());
            seen.add(boundValues());
        });
        seen.add(boundValues());

        assertThat(seen).containsExactly(
                List.of("acme.tenant=t2", "acme.region unbound", "acme.user=alice"),
                List.of("acme.tenant unbound", "acme.region=ap", "acme.user=alice"),
                List.of("acme.tenant=t2", "acme.region unbound", "acme.user=alice"),
                List.of("acme.tenant=t1", "acme.region=us", "acme.user unbound"),
                List.of("acme.tenant unbound", "acme.region unbound", "acme.user unbound"));
        assertThat(afterInner.get()).isSameAs(beforeInner.get());
        assertThat(afterMiddle.get()).isSameAs(beforeMiddle.get());
    }

    @Test
    @DisplayName("CORE-07 restores the previous snapshot when the operation throws")
    void restoresThePreviousSnapshotWhenTheOperationThrows() {
        IllegalStateException runtimeFailure = new IllegalStateException("boom");
        IOException checkedFailure = new IOException("boom");
        List<List<String>> seen = new ArrayList<>();

        Context.with(TENANT, "t1").run(() -> {
            catchThrowableOfType(IllegalStateException.class,
                    () -> Context.with(TENANT, "t2").with(USER, "alice").run(() -> {
                        throw runtimeFailure;
                    }));
            seen.add(boundValues());
            catchThrowableOfType(IOException.class,
                    () -> Context.with(REGION, "us").without(TENANT).<Void, IOException>call(() -> {
                        throw checkedFailure;
                    }));
            seen.add(boundValues());
        });
        catchThrowableOfType(IllegalStateException.class, () -> Context.with(TENANT, "t3").run(() -> {
            throw runtimeFailure;
        }));
        seen.add(boundValues());

        assertThat(seen).containsExactly(
                List.of("acme.tenant=t1", "acme.region unbound", "acme.user unbound"),
                List.of("acme.tenant=t1", "acme.region unbound", "acme.user unbound"),
                List.of("acme.tenant unbound", "acme.region unbound", "acme.user unbound"));
    }

    @Test
    @DisplayName("CORE-08 call returns the result and rethrows checked exceptions unchanged")
    void callReturnsTheResultAndRethrowsCheckedExceptionsUnchanged() {
        IOException checkedFailure = new IOException("boom");
        IllegalStateException runtimeFailure = new IllegalStateException("boom");
        ContextBinding binding = Context.with(TENANT, "t1");

        String result = binding.call(TENANT::get);
        IOException checked = catchThrowableOfType(IOException.class, () -> binding.<Void, IOException>call(() -> {
            throw checkedFailure;
        }));
        IllegalStateException unchecked = catchThrowableOfType(IllegalStateException.class, () -> binding.call(() -> {
            throw runtimeFailure;
        }));

        assertThat(result).isEqualTo("t1");
        assertThat(checked).isSameAs(checkedFailure);
        assertThat(unchecked).isSameAs(runtimeFailure);
    }

    @Test
    @DisplayName("CORE-06 with and without return a new binding")
    void withAndWithoutReturnANewBinding() {
        ContextBinding tenantOnly = Context.with(TENANT, "t1");
        ContextBinding withRegion = tenantOnly.with(REGION, "us");
        ContextBinding withoutTenant = tenantOnly.without(TENANT);
        ContextBinding removal = Context.without(TENANT);
        ContextBinding removalWithTenant = removal.with(TENANT, "t2");
        List<List<String>> seen = new ArrayList<>();

        tenantOnly.run(() -> seen.add(boundValues()));
        Context.with(TENANT, "t0").run(() -> removal.run(() -> seen.add(boundValues())));

        assertThat(withRegion).isNotSameAs(tenantOnly);
        assertThat(withoutTenant).isNotSameAs(tenantOnly);
        assertThat(removalWithTenant).isNotSameAs(removal);
        assertThat(seen).containsExactly(
                List.of("acme.tenant=t1", "acme.region unbound", "acme.user unbound"),
                List.of("acme.tenant unbound", "acme.region unbound", "acme.user unbound"));
    }

    @Test
    @DisplayName("RT-03 a pooled thread carries no residue after a scope exits exceptionally")
    void aPooledThreadCarriesNoResidueAfterAScopeExitsExceptionally() throws Exception {
        AtomicReference<Thread> nextThread = new AtomicReference<>();
        AtomicReference<ContextSnapshot> nextCapture = new AtomicReference<>();
        Thread throwingThread;
        List<String> seenByNextTask;

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            throwingThread = pool.submit(() -> {
                try {
                    Context.with(TENANT, "t1").with(USER, "alice").run(() -> {
                        throw new IllegalStateException("boom");
                    });
                } catch (IllegalStateException expected) {
                    // the scope exited exceptionally; the worker thread survives
                }
                return Thread.currentThread();
            }).get();
            seenByNextTask = pool.submit(() -> {
                nextThread.set(Thread.currentThread());
                nextCapture.set(Context.capture());
                return boundValues();
            }).get();
        }

        assertThat(nextThread.get()).isSameAs(throwingThread);
        assertThat(seenByNextTask).containsExactly("acme.tenant unbound", "acme.region unbound", "acme.user unbound");
        assertThat(nextCapture.get()).isSameAs(ContextSnapshot.EMPTY);
    }

    @Test
    @DisplayName("CORE-03 without rejects a null key with NullPointerException at the without call")
    void withoutRejectsANullKeyAtTheWithoutCall() {
        ContextBinding binding = Context.with(TENANT, "t1");

        assertThatNullPointerException().isThrownBy(() -> Context.without(null));
        assertThatNullPointerException().isThrownBy(() -> binding.without(null));
    }

    @Test
    @DisplayName("CORE-03 call rejects a null operation with NullPointerException and leaves the current snapshot unchanged")
    void callRejectsANullOperationAndLeavesTheCurrentSnapshotUnchanged() {
        assertThatNullPointerException().isThrownBy(() -> Context.with(TENANT, "t1").call(null));

        assertThat(TENANT.isBound()).isFalse();
        assertThat(Context.capture()).isSameAs(ContextSnapshot.EMPTY);
    }

    @Test
    @DisplayName("CORE-03 binding null throws NullPointerException at the with call")
    void bindingNullThrowsNullPointerExceptionAtTheWithCall() {
        ContextBinding binding = Context.with(REGION, "us");

        assertThatNullPointerException().isThrownBy(() -> Context.with(TENANT, null));
        assertThatNullPointerException().isThrownBy(() -> binding.with(TENANT, null));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    @DisplayName("CORE-04 binding a value of the wrong type throws ClassCastException at the with call")
    void bindingAValueOfTheWrongTypeThrowsClassCastExceptionAtTheWithCall() {
        ContextKey raw = TENANT;
        ContextBinding binding = Context.with(REGION, "us");

        assertThatExceptionOfType(ClassCastException.class).isThrownBy(() -> Context.with(raw, 42));
        assertThatExceptionOfType(ClassCastException.class).isThrownBy(() -> binding.with(raw, 42));
    }
}
