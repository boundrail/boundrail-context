package io.github.boundrail.context;

/** The built-in keys, whose names use the reserved prefix {@code boundrail.} (CORE-01). */
public final class BuiltInKeys {

    /** The current deadline (DL-02). */
    public static final ContextKey<Deadline> DEADLINE = ContextKey.builtIn("boundrail.deadline", Deadline.class);

    /** The caller's authority. */
    public static final ContextKey<Authority> AUTHORITY = ContextKey.builtIn("boundrail.authority", Authority.class);

    /** Who started the work. */
    public static final ContextKey<Initiator> INITIATOR = ContextKey.builtIn("boundrail.initiator", Initiator.class);

    private BuiltInKeys() {
    }
}
