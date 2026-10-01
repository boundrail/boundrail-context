package io.github.boundrail.context;

/** Thrown when a deadline has expired; {@link #stage()} tells where it was detected (DL-01). */
public final class DeadlineExceededException extends RuntimeException {

    private final Stage stage;

    DeadlineExceededException(Stage stage) {
        super("Deadline exceeded at stage " + stage);
        this.stage = stage;
    }

    /** {@return where the expired deadline was detected} */
    public Stage stage() {
        return stage;
    }

    /** Where an expired deadline was detected. */
    public enum Stage { INBOUND, BEFORE_SEND, TIMEOUT, EXECUTOR, APPLICATION }
}
