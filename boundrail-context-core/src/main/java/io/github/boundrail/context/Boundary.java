package io.github.boundrail.context;

/** The boundaries a context key can propagate across (POL-01 – POL-03). */
public enum Boundary {
    ATTACHED, DETACHED, INTERNAL_CALL, EXTERNAL_CALL, MESSAGE
}
