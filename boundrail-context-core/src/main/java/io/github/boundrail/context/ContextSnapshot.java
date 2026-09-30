package io.github.boundrail.context;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

/** An immutable set of key bindings; the current snapshot is the one visible to the running code. */
public interface ContextSnapshot {

    /** The snapshot with no bindings. */
    ContextSnapshot EMPTY = new ImmutableContextSnapshot(Map.of());

    /**
     * Returns the value bound to a key in this snapshot. Key defaults do not count (CORE-06).
     *
     * @param key the key
     * @param <T> the value type
     * @return the bound value, or empty if the key is not bound in this snapshot
     * @throws NullPointerException if the key is {@code null}
     */
    <T> Optional<T> find(ContextKey<T> key);

    /** {@return the keys bound in this snapshot, as a set that rejects modification (CORE-06)} */
    Set<ContextKey<?>> keys();

    /** {@return whether this snapshot binds no key} */
    boolean isEmpty();

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
