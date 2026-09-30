package io.github.boundrail.context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** An immutable list of changes to the current snapshot, applied for the scope of one operation (CORE-07). */
public final class ContextBinding {

    private record Change(ContextKey<?> key, Object value) {
    }

    private final List<Change> changes;

    private ContextBinding(List<Change> changes) {
        this.changes = changes;
    }

    static <T> ContextBinding of(ContextKey<T> key, T value) {
        return new ContextBinding(List.of()).with(key, value);
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
        List<Change> next = new ArrayList<>(changes);
        next.add(new Change(key, value));
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
        Map<ContextKey<?>, Object> values = new HashMap<>(ImmutableContextSnapshot.current().values());
        for (Change change : changes) {
            values.put(change.key(), change.value());
        }
        ContextRuntime.SLOT.call(new ImmutableContextSnapshot(values), () -> {
            op.run();
            return null;
        });
    }
}
