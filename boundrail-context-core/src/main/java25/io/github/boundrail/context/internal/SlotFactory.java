package io.github.boundrail.context.internal;

/**
 * Creates the runtime's slot. This Java 25 variant uses the ScopedValue backend unless the override property forces the ThreadLocal backend
 * (RT-02, RT-04).
 */
public final class SlotFactory {

    private SlotFactory() {
    }

    /** {@return a new slot, for the backend the {@code boundrail.context.backend} system property selects} */
    public static ContextSlot create() {
        return create(BackendOverride.forcesThreadLocal(System.getProperty(BackendOverride.PROPERTY),
                System.getLogger("io.github.boundrail.context")));
    }

    /**
     * Creates a slot.
     *
     * @param forceThreadLocal whether the override forces the ThreadLocal backend
     * @return a new ThreadLocal slot if forced, else a new ScopedValue slot
     */
    static ContextSlot create(boolean forceThreadLocal) {
        return forceThreadLocal ? new ThreadLocalSlot() : new ScopedValueSlot();
    }
}
