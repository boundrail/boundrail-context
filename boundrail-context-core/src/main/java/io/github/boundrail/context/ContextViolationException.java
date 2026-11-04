package io.github.boundrail.context;

import java.util.Objects;

/** Thrown when a violation fails an operation; {@link #violation()} carries its evidence (DIAG-02). */
public final class ContextViolationException extends RuntimeException {

    private final transient Violation violation;

    /**
     * Creates the exception with a message naming the violation's kind, key and boundary.
     *
     * @param violation the violation that failed the operation
     */
    public ContextViolationException(Violation violation) {
        super(message(violation));
        this.violation = violation;
    }

    /** {@return the violation that failed the operation} */
    public Violation violation() {
        return violation;
    }

    private static String message(Violation violation) {
        Objects.requireNonNull(violation, "violation");
        return "Context violation " + violation.kind() + " for key " + violation.key()
                + " at boundary " + violation.boundary();
    }
}
