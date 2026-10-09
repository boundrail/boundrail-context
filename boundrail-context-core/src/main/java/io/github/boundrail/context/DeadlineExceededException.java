package io.github.boundrail.context;

import java.util.Objects;

/** Thrown when a deadline has expired; {@link #stage()} tells where it was detected (DL-01). */
public final class DeadlineExceededException extends RuntimeException {

    private final Stage stage;

    /** Creates the exception with the message {@code Deadline exceeded at stage <STAGE>} and no cause (DL-12). */
    public DeadlineExceededException(Stage stage) {
        super(message(stage));
        this.stage = stage;
    }

    /** Creates the exception with the same message as {@link #DeadlineExceededException(Stage)} and the given cause (DL-12). */
    public DeadlineExceededException(Stage stage, Throwable cause) {
        super(message(stage), Objects.requireNonNull(cause, "cause"));
        this.stage = stage;
    }

    /** Returns the message for {@code stage}; a {@code null} stage throws {@code NullPointerException} before {@code super} runs (CORE-15). */
    private static String message(Stage stage) {
        Objects.requireNonNull(stage, "stage");
        return "Deadline exceeded at stage " + stage;
    }

    /** {@return where the expired deadline was detected} */
    public Stage stage() {
        return stage;
    }

    /** Where an expired deadline was detected. */
    public enum Stage { INBOUND, BEFORE_SEND, TIMEOUT, EXECUTOR, APPLICATION }
}
