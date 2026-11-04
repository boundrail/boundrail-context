package io.github.boundrail.context;

/** The boundaries a context key can propagate across (POL-01 – POL-03). */
public enum Boundary {
    /** In-process work that stays part of the current operation, such as a task on an {@code attached} executor. */
    ATTACHED,
    /** In-process work that can outlive the current operation, such as a task on a {@code detached} executor. */
    DETACHED,
    /** An HTTP call to an internal service; inbound HTTP requests are also checked and reported at this boundary (IN-02, DIAG-08). */
    INTERNAL_CALL,
    /** An outbound call to an external or unknown destination (DEST-03). */
    EXTERNAL_CALL,
    /** A message sent or received through a broker, such as a Kafka record. */
    MESSAGE
}
