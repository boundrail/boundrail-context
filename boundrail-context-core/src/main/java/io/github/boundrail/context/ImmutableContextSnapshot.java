package io.github.boundrail.context;

import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/** The library's snapshot: an immutable map from key to value. Keys compare by instance (CORE-02). */
final class ImmutableContextSnapshot implements ContextSnapshot {

    private final Map<ContextKey<?>, Object> values;

    ImmutableContextSnapshot(Map<ContextKey<?>, Object> values) {
        this.values = Map.copyOf(values);
    }

    /** {@return the snapshot current on this thread} */
    static ImmutableContextSnapshot current() {
        return (ImmutableContextSnapshot) ContextRuntime.SLOT.current();
    }

    Map<ContextKey<?>, Object> values() {
        return values;
    }

    @Override
    public <T> Optional<T> find(ContextKey<T> key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(key.type().cast(values.get(key)));
    }

    @Override
    public Set<ContextKey<?>> keys() {
        return Collections.unmodifiableSet(values.keySet());
    }

    @Override
    public boolean isEmpty() {
        return values.isEmpty();
    }

    @Override
    public String toString() {
        return values.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().name()))
                .map(entry -> entry.getKey().name() + "=" + entry.getValue())
                .collect(Collectors.joining(", ", "ContextSnapshot[", "]"));
    }

    @Override
    public void run(Runnable op) {
        Objects.requireNonNull(op, "op");
        ContextRuntime.enter(this, () -> {
            op.run();
            return null;
        });
    }

    @Override
    public <R, X extends Throwable> R call(ContextCallable<R, X> op) throws X {
        Objects.requireNonNull(op, "op");
        return ContextRuntime.enter(this, op);
    }

    @Override
    public Runnable wrap(Runnable op) {
        Objects.requireNonNull(op, "op");
        return () -> run(op);
    }

    @Override
    public <R> Callable<R> wrap(Callable<R> op) {
        Objects.requireNonNull(op, "op");
        return () -> call(op::call);
    }
}
