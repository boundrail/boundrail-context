package io.github.boundrail.context;

import java.util.Map;

/** The library's snapshot: an immutable map from key to value. Keys compare by instance (CORE-02). */
final class ImmutableContextSnapshot implements ContextSnapshot {

    private final Map<ContextKey<?>, Object> values;

    ImmutableContextSnapshot(Map<ContextKey<?>, Object> values) {
        this.values = Map.copyOf(values);
    }

    /** {@return the snapshot current on this thread} */
    static ImmutableContextSnapshot current() {
        return (ImmutableContextSnapshot) ContextRuntime.SLOT.current();
    }

    Map<ContextKey<?>, Object> values() {
        return values;
    }
}
