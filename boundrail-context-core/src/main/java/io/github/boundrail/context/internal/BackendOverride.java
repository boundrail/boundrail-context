package io.github.boundrail.context.internal;

/**
 * Reads the value of the {@code boundrail.context.backend} system property (RT-04). {@code thread-local} forces the ThreadLocal backend;
 * unset and {@code auto} select automatically; any other value, compared exactly, logs a warning and selects automatically.
 */
final class BackendOverride {

    static final String PROPERTY = "boundrail.context.backend";

    private BackendOverride() {
    }

    /**
     * Tells whether a value of the override property forces the ThreadLocal backend.
     *
     * @param value the property value, or {@code null} if unset
     * @param logger the logger that receives the warning for an unrecognised value
     * @return {@code true} if the value forces the ThreadLocal backend, {@code false} for automatic selection
     */
    static boolean forcesThreadLocal(String value, System.Logger logger) {
        if (value == null || value.equals("auto")) {
            return false;
        }
        if (value.equals("thread-local")) {
            return true;
        }
        logger.log(System.Logger.Level.WARNING, "Unrecognised value \"{0}\" for {1}; using automatic backend selection", value, PROPERTY);
        return false;
    }
}
