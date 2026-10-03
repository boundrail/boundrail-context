package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScopeListenerTest {

    private static final ContextKey<String> TENANT = ContextKey.of("acme.tenant", String.class);

    private final List<String> events = new ArrayList<>();
    private final List<ScopeListener> registered = new ArrayList<>();

    @AfterEach
    void removeListeners() {
        registered.forEach(ContextRuntime::removeScopeListener);
    }

    private void register(ScopeListener listener) {
        registered.add(listener);
        ContextRuntime.addScopeListener(listener);
    }

    /** The tenant bound in the current snapshot, or "-". */
    private static String tenant() {
        return Context.capture().find(TENANT).orElse("-");
    }

    /** A listener that records its name and the current tenant on entry and on restore, and whether the entered snapshot is current. */
    private ScopeListener recording(String name) {
        return entered -> {
            events.add("enter " + name + " " + tenant() + (Context.capture() == entered ? "" : " (entered is not current)"));
            return () -> events.add("restore " + name + " " + tenant());
        };
    }

    /** Every way of entering a scope, each entering a snapshot that binds tenant=t1. */
    private static Map<String, Runnable> scopeEntries(ContextSnapshot t1) {
        ContextBinding binding = Context.with(TENANT, "t1");
        Map<String, Runnable> entries = new LinkedHashMap<>();
        entries.put("ContextBinding.run", () -> binding.run(() -> { }));
        entries.put("ContextBinding.call", () -> binding.call(() -> "result"));
        entries.put("ContextSnapshot.run", () -> t1.run(() -> { }));
        entries.put("ContextSnapshot.call", () -> t1.call(() -> "result"));
        entries.put("ContextSnapshot.wrap(Runnable)", () -> t1.wrap(() -> { }).run());
        entries.put("ContextSnapshot.wrap(Callable)", () -> {
            try {
                t1.wrap(() -> "result").call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        return entries;
    }

    @Test
    @DisplayName("RT-07 listeners are entered in registration order after the entered snapshot is current")
    void listenersAreEnteredInRegistrationOrderAfterTheEnteredSnapshotIsCurrent() {
        ContextSnapshot t1 = Context.with(TENANT, "t1").call(Context::capture);
        register(recording("A"));
        register(recording("B"));

        scopeEntries(t1).forEach((name, entry) -> {
            events.clear();
            entry.run();
            assertThat(events).as(name).startsWith("enter A t1", "enter B t1");
        });
    }

    @Test
    @DisplayName("RT-07 restorers run in reverse order before the previous snapshot is restored")
    void restorersRunInReverseOrderBeforeThePreviousSnapshotIsRestored() {
        ContextSnapshot t1 = Context.with(TENANT, "t1").call(Context::capture);
        register(recording("A"));
        register(recording("B"));

        scopeEntries(t1).forEach((name, entry) -> {
            events.clear();
            entry.run();
            assertThat(events).as(name).containsExactly("enter A t1", "enter B t1", "restore B t1", "restore A t1");
        });
    }

    @Test
    @DisplayName("RT-07 every nested scope is entered and restored")
    void everyNestedScopeIsEnteredAndRestored() {
        register(recording("A"));
        register(recording("B"));

        Context.with(TENANT, "t1").run(() -> Context.with(TENANT, "t2").run(() -> Context.with(TENANT, "t3").run(() -> { })));

        assertThat(events).containsExactly(
                "enter A t1", "enter B t1",
                "enter A t2", "enter B t2",
                "enter A t3", "enter B t3",
                "restore B t3", "restore A t3",
                "restore B t2", "restore A t2",
                "restore B t1", "restore A t1");
    }

    @Test
    @DisplayName("RT-07 restorers run when the operation throws, and the exception is rethrown unchanged")
    void restorersRunWhenTheOperationThrowsAndTheExceptionIsRethrownUnchanged() {
        register(recording("A"));
        register(recording("B"));
        IOException thrown = new IOException("boom");

        IOException caught = catchThrowableOfType(IOException.class, () -> Context.with(TENANT, "t1").call(() -> {
            throw thrown;
        }));

        assertThat(caught).isSameAs(thrown);
        assertThat(events).containsExactly("enter A t1", "enter B t1", "restore B t1", "restore A t1");
    }

    @Test
    @DisplayName("RT-07 a removed listener is no longer called")
    void aRemovedListenerIsNoLongerCalled() {
        ScopeListener a = recording("A");
        register(a);
        register(recording("B"));

        ContextRuntime.removeScopeListener(a);
        Context.with(TENANT, "t1").run(() -> { });

        assertThat(events).containsExactly("enter B t1", "restore B t1");
    }

    @Test
    @DisplayName("CORE-14 addScopeListener(null) and removeScopeListener(null) throw NullPointerException")
    void addAndRemoveScopeListenerRejectNull() {
        assertThatNullPointerException().isThrownBy(() -> ContextRuntime.addScopeListener(null));
        assertThatNullPointerException().isThrownBy(() -> ContextRuntime.removeScopeListener(null));
    }
}
