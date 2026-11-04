package io.github.boundrail.context;

import java.util.Objects;

/** Thrown when a deadline has expired; {@link #stage()} tells where it was detected (DL-01). */
public final class DeadlineExceededException extends RuntimeException {

    /** Where the expired deadline was detected. */
    private final Stage stage;

    /**
     * Creates the exception with the message {@code Deadline exceeded at stage <STAGE>} and no cause (DL-12).
     *
     * @param stage where the deadline was found expired
     */
    public DeadlineExceededException(Stage stage) {
        super(message(stage));
        this.stage = stage;
    }

    /**
     * Creates the exception with the same message as {@link #DeadlineExceededException(Stage)} and the given cause (DL-12).
     *
     * @param stage where the deadline was found expired
     * @param cause the exception that made the deadline observable, for example the client's exception after the timer fired
     */
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
    public enum Stage {
        /**
         * On an inbound request, before its handler ran; the filter sets the expired status and throws nothing, so this stage only
         * tags the metric (DL-04, DL-09).
         */
        INBOUND,
        /** Before an outbound call was sent. */
        BEFORE_SEND,
        /** After {@code BEFORE_SEND} passed: while an outbound call awaited its response headers (DL-07), or at its token exchange (ID-12). */
        TIMEOUT,
        /** When a task from {@code BoundExecutors.attached}, or a Spring MVC handler's {@code Callable}, was about to start (EXE-08). */
        EXECUTOR,
        /** In application code, through {@link Deadline#check()}. */
        APPLICATION
    }
}
