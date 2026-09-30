package io.github.boundrail.context.internal;

/** Creates the runtime's slot. This base variant always uses the ThreadLocal backend (RT-02). */
public final class SlotFactory {

    private SlotFactory() {
    }

    /** {@return a new slot} */
    public static ContextSlot create() {
        return new ThreadLocalSlot();
    }
}
