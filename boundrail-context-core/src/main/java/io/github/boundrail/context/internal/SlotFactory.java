package io.github.boundrail.context.internal;

/**
 * Creates the runtime's slot. This base variant always uses the ThreadLocal backend (RT-02), and still reads the override property so that an
 * unrecognised value warns on every Java version (RT-04).
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
     * @return a new ThreadLocal slot
     */
    static ContextSlot create(boolean forceThreadLocal) {
        return new ThreadLocalSlot();
    }
}
