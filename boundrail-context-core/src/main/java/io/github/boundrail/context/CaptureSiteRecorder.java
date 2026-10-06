package io.github.boundrail.context;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Records the capture site of a snapshot a binding creates, according to the capture-site mode (DIAG-05, DIAG-10). Under {@code OFF} no stack
 * is walked (SC-003).
 */
final class CaptureSiteRecorder {

    private static final String CORE_PACKAGE = "io.github.boundrail.context";
    private static final String INTERNAL_PACKAGE = "io.github.boundrail.context.internal";
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();

    /**
     * Walks the stack and returns the capture site, or {@code null}. A test seam only, replaced by a core test to count walks (SC-003); it is
     * not configuration.
     */
    static Supplier<String> walker = CaptureSiteRecorder::walk;

    private CaptureSiteRecorder() {
    }

    /**
     * {@return the capture site for a snapshot being created now, or {@code null} if the mode records none; under {@code SAMPLED} a binding
     * records with the probability of the sample rate}
     */
    static String record() {
        ContextRuntime.CaptureSiteMode current = ContextRuntime.captureSiteMode();
        return switch (current.mode()) {
            case ON -> walker.get();
            case SAMPLED -> ThreadLocalRandom.current().nextDouble() < current.sampleRate() ? walker.get() : null;
            case OFF -> null;
        };
    }

    /** {@return the first frame whose class is in neither of the library's core packages, in StackTraceElement.toString() form, or null} */
    private static String walk() {
        return STACK_WALKER.walk(frames -> frames
                .filter(frame -> !isLibraryClass(frame.getClassName()))
                .findFirst()
                .map(frame -> frame.toStackTraceElement().toString())
                .orElse(null));
    }

    private static boolean isLibraryClass(String className) {
        int lastDot = className.lastIndexOf('.');
        String packageName = lastDot < 0 ? "" : className.substring(0, lastDot);
        return packageName.equals(CORE_PACKAGE) || packageName.equals(INTERNAL_PACKAGE);
    }
}
