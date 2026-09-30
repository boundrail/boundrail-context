package io.github.boundrail.context.internal;

import io.github.boundrail.context.ContextCallable;
import io.github.boundrail.context.ContextSnapshot;

/** The single slot per thread of execution that holds the current snapshot (RT-01). */
public interface ContextSlot {

    /** {@return the current snapshot, or {@link ContextSnapshot#EMPTY} if none is current} */
    ContextSnapshot current();

    /**
     * Runs an operation with a snapshot as current, and makes the previous snapshot current again afterwards.
     *
     * @param snapshot the snapshot to make current
     * @param op the operation
     * @param <R> the result type
     * @param <X> the exception type
     * @return the operation's result
     * @throws X if the operation throws
     */
    <R, X extends Throwable> R call(ContextSnapshot snapshot, ContextCallable<R, X> op) throws X;
}
