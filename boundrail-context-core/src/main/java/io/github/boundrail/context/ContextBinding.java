package io.github.boundrail.context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** An immutable list of changes to the current snapshot, applied for the scope of one operation (CORE-07). */
public final class ContextBinding {

    /** The value of a change that removes its key. */
    private static final Object REMOVED = new Object();

    private record Change(ContextKey<?> key, Object value) {
    }

    private final List<Change> changes;

    private ContextBinding(List<Change> changes) {
        this.changes = changes;
    }

    static <T> ContextBinding of(ContextKey<T> key, T value) {
        return new ContextBinding(List.of()).with(key, value);
    }

    static ContextBinding removing(ContextKey<?> key) {
        return new ContextBinding(List.of()).without(key);
    }

    /**
     * Returns a binding that also binds a key to a value.
     *
     * @param key the key
     * @param value the value
     * @param <T> the value type
     * @return a new binding
     * @throws NullPointerException if the key is {@code null}
     */
    public <T> ContextBinding with(ContextKey<T> key, T value) {
        Objects.requireNonNull(key, "key");
        return adding(new Change(key, value));
    }

    /**
     * Returns a binding that also removes a key.
     *
     * @param key the key
     * @return a new binding
     * @throws NullPointerException if the key is {@code null}
     */
    public ContextBinding without(ContextKey<?> key) {
        Objects.requireNonNull(key, "key");
        return adding(new Change(key, REMOVED));
    }

    private ContextBinding adding(Change change) {
        List<Change> next = new ArrayList<>(changes);
        next.add(change);
        return new ContextBinding(List.copyOf(next));
    }

    /**
     * Runs an operation with the current snapshot plus this binding's changes, then restores the previous snapshot.
     *
     * @param op the operation
     * @throws NullPointerException if the operation is {@code null}
     */
    public void run(Runnable op) {
        Objects.requireNonNull(op, "op");
        ContextRuntime.SLOT.call(applied(), () -> {
            op.run();
            return null;
        });
    }

    /**
     * Calls an operation with the current snapshot plus this binding's changes, then restores the previous snapshot (CORE-08).
     *
     * @param op the operation
     * @param <R> the result type
     * @param <X> the exception type
     * @return the operation's result
     * @throws X if the operation throws; it is rethrown unchanged
     * @throws NullPointerException if the operation is {@code null}
     */
    public <R, X extends Throwable> R call(ContextCallable<R, X> op) throws X {
        Objects.requireNonNull(op, "op");
        return ContextRuntime.SLOT.call(applied(), op);
    }

    /** {@return the current snapshot with this binding's changes applied in call order, so the last change for a key wins} */
    private ImmutableContextSnapshot applied() {
        Map<ContextKey<?>, Object> values = new HashMap<>(ImmutableContextSnapshot.current().values());
        for (Change change : changes) {
            if (change.value() == REMOVED) {
                values.remove(change.key());
            } else {
                values.put(change.key(), change.value());
            }
        }
        return new ImmutableContextSnapshot(values);
    }
}
