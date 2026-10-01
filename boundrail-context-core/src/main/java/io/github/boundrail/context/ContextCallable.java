package io.github.boundrail.context;

/**
 * An operation that returns a result and may throw a checked exception of type {@code X} (CORE-08).
 *
 * @param <R> the result type
 * @param <X> the exception type
 */
@FunctionalInterface
public interface ContextCallable<R, X extends Throwable> {

    /**
     * Runs the operation.
     *
     * @return the result
     * @throws X if the operation fails
     */
    R call() throws X;
}
