package io.github.boundrail.context;

import java.util.Map;

/** An immutable set of key bindings; the current snapshot is the one visible to the running code. */
public interface ContextSnapshot {

    /** The snapshot with no bindings. */
    ContextSnapshot EMPTY = new ImmutableContextSnapshot(Map.of());

    /**
     * Runs an operation with exactly this snapshot as current, replacing the caller's snapshot, then restores the caller's snapshot
     * (CORE-10).
     *
     * @param op the operation
     * @throws NullPointerException if the operation is {@code null}
     */
    void run(Runnable op);

    /**
     * Calls an operation with exactly this snapshot as current, replacing the caller's snapshot, then restores the caller's snapshot
     * (CORE-10).
     *
     * @param op the operation
     * @param <R> the result type
     * @param <X> the exception type
     * @return the operation's result
     * @throws X if the operation throws; it is rethrown unchanged
     * @throws NullPointerException if the operation is {@code null}
     */
    <R, X extends Throwable> R call(ContextCallable<R, X> op) throws X;
}
