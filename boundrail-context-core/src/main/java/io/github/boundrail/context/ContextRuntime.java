package io.github.boundrail.context;

import io.github.boundrail.context.internal.ContextSlot;
import io.github.boundrail.context.internal.SlotFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** The runtime that holds the current snapshot in a single slot per thread of execution (RT-01). */
public final class ContextRuntime {

    /** The backends that can hold the current snapshot (RT-02). */
    public enum Backend {
        /** A {@link ThreadLocal} backend. */
        THREAD_LOCAL,
        /** A {@code ScopedValue} backend. */
        SCOPED_VALUE
    }

    static final ContextSlot SLOT = SlotFactory.create();

    private static final List<ScopeListener> LISTENERS = new CopyOnWriteArrayList<>();

    private ContextRuntime() {
    }

    /** {@return the active backend (RT-05)} */
    public static Backend backend() {
        return SLOT.backend();
    }

    /**
     * Registers a listener that is called whenever a scope is entered (RT-07).
     *
     * @param listener the listener
     * @throws NullPointerException if the listener is {@code null}
     */
    public static void addScopeListener(ScopeListener listener) {
        Objects.requireNonNull(listener, "listener");
        LISTENERS.add(listener);
    }

    /**
     * Removes a registered listener (RT-07).
     *
     * @param listener the listener
     * @throws NullPointerException if the listener is {@code null}
     */
    public static void removeScopeListener(ScopeListener listener) {
        Objects.requireNonNull(listener, "listener");
        LISTENERS.remove(listener);
    }

    /**
     * Calls an operation with a snapshot current, the single entry point for every scope (RT-07). Once the snapshot is current, each
     * listener is entered in registration order; when the operation returns or throws, their restorers run in reverse order, before the
     * previous snapshot is restored.
     */
    static <R, X extends Throwable> R enter(ContextSnapshot snapshot, ContextCallable<R, X> op) throws X {
        return SLOT.call(snapshot, () -> {
            if (LISTENERS.isEmpty()) {
                return op.call();
            }
            List<ScopeListener.Restorer> restorers = new ArrayList<>();
            for (ScopeListener listener : LISTENERS) {
                restorers.add(listener.onEnter(snapshot));
            }
            try {
                return op.call();
            } finally {
                for (int i = restorers.size() - 1; i >= 0; i--) {
                    restorers.get(i).restore();
                }
            }
        });
    }
}
