package io.github.boundrail.context;

/**
 * Called by the runtime whenever a scope is entered, after the entered snapshot becomes current, so that state derived from the context can
 * follow it (RT-07). Register with {@link ContextRuntime#addScopeListener(ScopeListener)}.
 */
@FunctionalInterface
public interface ScopeListener {

    /**
     * Called on the entering thread after {@code entered} becomes current. Listeners are called in registration order. A
     * {@code RuntimeException} thrown here is logged, naming this listener, and the scope is entered without this listener's restorer. An
     * {@code Error} is not caught: the operation and later listeners do not run, the restorers already returned run in reverse order, and the
     * {@code Error} propagates.
     *
     * @param entered the snapshot that is now current
     * @return the restorer to call when the scope exits; must not be {@code null}: the operation then does not run, the restorers already
     *     returned run in reverse order, and {@code NullPointerException} is thrown (RT-07)
     */
    Restorer onEnter(ContextSnapshot entered);

    /** Undoes what {@link ScopeListener#onEnter(ContextSnapshot)} did. */
    @FunctionalInterface
    interface Restorer {

        /**
         * Called on the same thread when the scope exits, before the previous snapshot is restored; restorers run in reverse order. A
         * {@code RuntimeException} thrown here is logged, naming the listener that returned this restorer, and the remaining restorers still
         * run; an {@code Error} propagates at once and the remaining restorers are skipped.
         */
        void restore();
    }
}
