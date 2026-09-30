package io.github.boundrail.context;

import io.github.boundrail.context.internal.ContextSlot;
import io.github.boundrail.context.internal.SlotFactory;

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

    private ContextRuntime() {
    }

    /** {@return the active backend (RT-05)} */
    public static Backend backend() {
        return SLOT.backend();
    }
}
