package io.github.boundrail.context;

import java.util.Map;
import java.util.Objects;

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

    @Override
    public void run(Runnable op) {
        Objects.requireNonNull(op, "op");
        ContextRuntime.SLOT.call(this, () -> {
            op.run();
            return null;
        });
    }

    @Override
    public <R, X extends Throwable> R call(ContextCallable<R, X> op) throws X {
        Objects.requireNonNull(op, "op");
        return ContextRuntime.SLOT.call(this, op);
    }
}
