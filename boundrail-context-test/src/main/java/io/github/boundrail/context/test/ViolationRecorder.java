package io.github.boundrail.context.test;

import io.github.boundrail.context.Violation;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the violations of the running {@code @BoundrailTest} test method, JVM-wide, so the violations of every application context reach
 * it (TEST-02). This is the one JVM-wide state allowed outside RT-09's list, because the test module is never on a production classpath
 * (RT-09, DESIGN D40). While no recording is active it keeps nothing.
 */
final class ViolationRecorder {

    /** The running test method's violations, or {@code null} while no {@code @BoundrailTest} test method is running. */
    private static volatile List<Violation> recording;

    private ViolationRecorder() {
    }

    /** Starts a fresh recording, for a test method that starts. */
    static void start() {
        recording = new CopyOnWriteArrayList<>();
    }

    /** Stops recording, for a test method that ended. */
    static void stop() {
        recording = null;
    }

    /** Keeps the violation if a recording is active. */
    static void record(Violation violation) {
        List<Violation> current = recording;
        if (current != null) {
            current.add(violation);
        }
    }

    /**
     * {@return the violations recorded so far for the running test method, in reporting order}
     *
     * @throws IllegalStateException if no {@code @BoundrailTest} test method is running
     */
    static List<Violation> all() {
        List<Violation> current = recording;
        if (current == null) {
            throw new IllegalStateException("No @BoundrailTest test method is running");
        }
        return List.copyOf(current);
    }
}
