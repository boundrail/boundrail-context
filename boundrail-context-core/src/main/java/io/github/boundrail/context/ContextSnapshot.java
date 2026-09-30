package io.github.boundrail.context;

import java.util.Map;
import java.util.concurrent.Callable;

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

    /**
     * Returns an operation that, when run later on any thread, behaves like {@link #run(Runnable)} with this snapshot (CORE-10).
     *
     * @param op the operation
     * @return the wrapped operation
     * @throws NullPointerException if the operation is {@code null}
     */
    Runnable wrap(Runnable op);

    /**
     * Returns an operation that, when called later on any thread, behaves like {@link #call(ContextCallable)} with this snapshot and
     * returns the operation's result (CORE-10).
     *
     * @param op the operation
     * @param <R> the result type
     * @return the wrapped operation
     * @throws NullPointerException if the operation is {@code null}
     */
    <R> Callable<R> wrap(Callable<R> op);
}
