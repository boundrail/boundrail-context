package io.github.boundrail.context.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.MissingContextException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runtime conformance suite (BLD-06, RT-06): the same scenarios must pass on the ThreadLocal and ScopedValue backends, so this suite
 * runs in all three integration test tasks. Scenarios come from specs/002-core-runtime (RC-1 – RC-8) and specs/004-async-diagnostics
 * (RC-9); each body is written test-first when its feature enables it (BLD-10).
 */
class RuntimeConformanceIT {

    @Test
    @Disabled("Pending: M1")
    @DisplayName("RC-1 active backend matches the JDK and the override")
    void rc1ActiveBackendMatchesTheJDKAndTheOverride() {
        // RC-1: The active backend is `SCOPED_VALUE` under `integrationTestJdk25`, and `THREAD_LOCAL` under `integrationTestJdk21` and
        // `integrationTestJdk25ThreadLocal`. *(RT-02, RT-04, RT-05)*
    }

    @Test
    @Disabled("Pending: M1")
    @DisplayName("RC-2 nested bindings restore the exact previous snapshot")
    void rc2NestedBindingsRestoreTheExactPreviousSnapshot() {
        // RC-2: Nested bindings rebind and remove keys; each scope exit restores the exact previous snapshot, including when the operation
        // throws a runtime exception or a checked exception. *(CORE-07, CORE-08)*
    }

    @Test
    @Disabled("Pending: M1")
    @DisplayName("RC-3 snapshot run replaces the caller snapshot")
    void rc3SnapshotRunReplacesTheCallerSnapshot() {
        // RC-3: `snapshot.run` replaces the caller's snapshot rather than merging with it, and restores it afterwards. *(CORE-10)*
    }

    @Test
    @Disabled("Pending: M1")
    @DisplayName("RC-4 one snapshot is shared safely across virtual threads")
    void rc4OneSnapshotIsSharedSafelyAcrossVirtualThreads() {
        // RC-4: One snapshot run concurrently on 1,000 virtual threads makes the same values visible on every thread, and bindings made
        // inside those threads never become visible to the others. *(CORE-11)*
    }

    @Test
    @Disabled("Pending: M1")
    @DisplayName("RC-5 pooled threads carry no residue")
    void rc5PooledThreadsCarryNoResidue() {
        // RC-5: After a scope exits on a pooled platform thread, the next task on that thread observes `EMPTY`. *(RT-03)*
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
    @Disabled("Pending: M1")
    @DisplayName("RC-7 null and wrong-type values are rejected when binding")
    void rc7NullAndWrongTypeValuesAreRejectedWhenBinding() {
        // RC-7: Binding `null` throws `NullPointerException`, and binding a value of the wrong type throws `ClassCastException` at the
        // binding call. *(CORE-03, CORE-04)*
    }

    @Test
    @Disabled("Pending: M1")
    @DisplayName("RC-8 deadlines can be shortened but never extended or removed")
    void rc8DeadlinesCanBeShortenedButNeverExtendedOrRemoved() {
        // RC-8: Within a scope whose deadline has 100ms remaining, binding a deadline of 10s leaves at most 100ms remaining, binding one
        // of 10ms shortens it to at most 10ms, and `Context.without(DEADLINE)` throws `IllegalArgumentException`. *(DL-10, DL-11)*
    }

    @Test
    @Disabled("Pending: M3")
    @DisplayName("RC-9 Scope listeners")
    void rc9ScopeListeners() {
        // RC-9: Two registered listeners are entered in registration order and restored in reverse order for every nested scope, including
        // when the operation throws. A listener that throws does not change the operation's result. *(RT-07)*
    }
}
