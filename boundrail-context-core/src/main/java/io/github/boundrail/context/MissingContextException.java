package io.github.boundrail.context;

/** Thrown by {@link ContextKey#get()} when the key has no value in the current snapshot and no default (CORE-05). */
public final class MissingContextException extends RuntimeException {

    private final transient ContextKey<?> key;

    MissingContextException(ContextKey<?> key) {
        super("No value for context key " + key.name());
        this.key = key;
    }

    /** {@return the key that has no value} */
    public ContextKey<?> key() {
        return key;
    }
}
