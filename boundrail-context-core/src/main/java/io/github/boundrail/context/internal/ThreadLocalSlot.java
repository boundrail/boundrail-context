package io.github.boundrail.context.internal;

import io.github.boundrail.context.ContextCallable;
import io.github.boundrail.context.ContextSnapshot;

/** The ThreadLocal backend of the slot (RT-01, RT-02). */
final class ThreadLocalSlot implements ContextSlot {

    private final ThreadLocal<ContextSnapshot> current = new ThreadLocal<>();

    @Override
    public ContextSnapshot current() {
        ContextSnapshot snapshot = current.get();
        return snapshot == null ? ContextSnapshot.EMPTY : snapshot;
    }

    @Override
    public <R, X extends Throwable> R call(ContextSnapshot snapshot, ContextCallable<R, X> op) throws X {
        ContextSnapshot previous = current.get();
        current.set(snapshot);
        R result = op.call();
        current.set(previous);
        return result;
    }
}
