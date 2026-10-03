package io.github.boundrail.context;

/**
 * Called by the runtime whenever a scope is entered, after the entered snapshot becomes current, so that state derived from the context can
 * follow it (RT-07). Register with {@link ContextRuntime#addScopeListener(ScopeListener)}.
 */
@FunctionalInterface
public interface ScopeListener {

    /**
     * Called on the entering thread after {@code entered} becomes current. Listeners are called in registration order.
     *
     * @param entered the snapshot that is now current
     * @return the restorer to call when the scope exits; must not be {@code null}
     */
    Restorer onEnter(ContextSnapshot entered);

    /** Undoes what {@link ScopeListener#onEnter(ContextSnapshot)} did. */
    @FunctionalInterface
    interface Restorer {

        /** Called on the same thread when the scope exits, before the previous snapshot is restored; restorers run in reverse order. */
        void restore();
    }
}
