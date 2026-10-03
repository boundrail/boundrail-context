package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScopeListenerTest {

    private static final ContextKey<String> TENANT = ContextKey.of("acme.tenant", String.class);

    private final List<String> events = new ArrayList<>();
    private final List<ScopeListener> registered = new ArrayList<>();

    /** The library's logger as the default System.LoggerFinder maps it to java.util.logging. */
    private final Logger julLogger = Logger.getLogger("io.github.boundrail.context");
    private final List<LogRecord> logRecords = new CopyOnWriteArrayList<>();
    private final Handler logCapture = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            logRecords.add(logRecord);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void captureLog() {
        julLogger.addHandler(logCapture);
    }

    @AfterEach
    void removeListeners() {
        registered.forEach(ContextRuntime::removeScopeListener);
        julLogger.removeHandler(logCapture);
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
            return () -> events.add("restore " + name + " " + tenant() + (Context.capture() == entered ? "" : " (entered is not current)"));
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

    /** The WARNING records logged since the last call, as their thrown exceptions. */
    private List<Throwable> warningsThrown() {
        List<Throwable> thrown = logRecords.stream().filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getThrown).toList();
        logRecords.clear();
        return thrown;
    }

    @Test
    @DisplayName("RT-07 a listener that throws a RuntimeException is logged and does not change the operation's outcome, and an Error propagates")
    void aListenerThatThrowsARuntimeExceptionIsLoggedAndDoesNotChangeTheOutcome() {
        IllegalStateException listenerFailure = new IllegalStateException("listener failed");
        ScopeListener throwing = entered -> {
            throw listenerFailure;
        };
        register(throwing);
        register(recording("B"));

        String result = Context.with(TENANT, "t1").call(() -> "result");

        assertThat(result).isEqualTo("result");
        assertThat(events).containsExactly("enter B t1", "restore B t1");
        assertThat(logRecords.stream().filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage).toList())
                .singleElement().asString().contains(throwing.getClass().getName());
        assertThat(warningsThrown()).containsExactly(listenerFailure);

        events.clear();
        IOException operationFailure = new IOException("boom");
        IOException caught = catchThrowableOfType(IOException.class, () -> Context.with(TENANT, "t1").call(() -> {
            throw operationFailure;
        }));

        assertThat(caught).isSameAs(operationFailure);
        assertThat(events).containsExactly("enter B t1", "restore B t1");
        assertThat(warningsThrown()).containsExactly(listenerFailure);

        ContextRuntime.removeScopeListener(throwing);
        AssertionError error = new AssertionError("listener error");
        register(entered -> {
            throw error;
        });

        assertThat(catchThrowableOfType(AssertionError.class, () -> Context.with(TENANT, "t1").run(() -> { }))).isSameAs(error);
    }

    @Test
    @DisplayName("RT-07 an Error from a later onEnter stops the entry: the operation and later listeners do not run, earlier restorers run in reverse order, and the Error propagates")
    void anErrorFromALaterOnEnterStopsTheEntry() {
        AssertionError error = new AssertionError("listener error");
        register(recording("A"));
        register(recording("B"));
        register(entered -> {
            events.add("enter C " + tenant());
            throw error;
        });
        register(recording("D"));

        AssertionError caught = catchThrowableOfType(AssertionError.class,
                () -> Context.with(TENANT, "t1").run(() -> events.add("operation")));

        assertThat(caught).isSameAs(error);
        assertThat(events).containsExactly("enter A t1", "enter B t1", "enter C t1", "restore B t1", "restore A t1");
    }

    @Test
    @DisplayName("RT-07 a restorer that throws a RuntimeException is logged naming the listener that returned it, and the remaining restorers still run, while an Error from a restorer propagates at once and the remaining restorers are skipped")
    void aRestorerThatThrowsIsLoggedNamingItsListenerAndAnErrorFromARestorerPropagatesAtOnce() {
        AtomicReference<Throwable> restorerFailure = new AtomicReference<>();
        ScopeListener b = entered -> {
            events.add("enter B " + tenant());
            return () -> {
                events.add("restore B " + tenant());
                switch (restorerFailure.get()) {
                    case RuntimeException e -> throw e;
                    case Error e -> throw e;
                    default -> throw new IllegalStateException("unexpected failure type");
                }
            };
        };
        register(recording("A"));
        register(b);
        register(recording("C"));

        IllegalStateException runtimeFailure = new IllegalStateException("restorer failed");
        restorerFailure.set(runtimeFailure);
        String result = Context.with(TENANT, "t1").call(() -> "result");

        assertThat(result).isEqualTo("result");
        assertThat(events).containsExactly("enter A t1", "enter B t1", "enter C t1", "restore C t1", "restore B t1", "restore A t1");
        List<LogRecord> warnings = logRecords.stream().filter(r -> r.getLevel() == Level.WARNING).toList();
        assertThat(warnings).singleElement().satisfies(warning -> {
            assertThat(warning.getThrown()).isSameAs(runtimeFailure);
            assertThat(warning.getMessage()).contains(b.getClass().getName());
        });

        events.clear();
        AssertionError error = new AssertionError("restorer error");
        restorerFailure.set(error);

        assertThat(catchThrowableOfType(AssertionError.class, () -> Context.with(TENANT, "t1").run(() -> { }))).isSameAs(error);
        assertThat(events).containsExactly("enter A t1", "enter B t1", "enter C t1", "restore C t1", "restore B t1");
    }

    @Test
    @DisplayName("RT-07 adding a registered listener keeps it once in its original position, and removing an unregistered listener does nothing")
    void addingARegisteredListenerKeepsItOnceAndRemovingAnUnregisteredListenerDoesNothing() {
        ScopeListener a = recording("A");
        register(a);
        register(recording("B"));
        register(a);

        ContextRuntime.removeScopeListener(recording("C"));
        Context.with(TENANT, "t1").run(() -> { });

        assertThat(events).containsExactly("enter A t1", "enter B t1", "restore B t1", "restore A t1");
    }

    @Test
    @DisplayName("RT-07 a listener whose onEnter returns null stops the scope entry: the operation does not run, earlier restorers run in reverse order, then NullPointerException is thrown")
    void aListenerWhoseOnEnterReturnsNullStopsTheScopeEntry() {
        register(recording("A"));
        register(recording("A2"));
        register(entered -> {
            events.add("enter B " + tenant());
            return null;
        });
        register(recording("C"));

        assertThatNullPointerException().isThrownBy(() -> Context.with(TENANT, "t1").run(() -> events.add("operation")));

        assertThat(events).containsExactly("enter A t1", "enter A2 t1", "enter B t1", "restore A2 t1", "restore A t1");
    }
}
