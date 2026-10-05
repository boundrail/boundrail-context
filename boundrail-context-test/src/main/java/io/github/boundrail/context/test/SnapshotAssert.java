package io.github.boundrail.context.test;

import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextSnapshot;
import java.util.Objects;
import java.util.Optional;
import org.assertj.core.api.AbstractAssert;

/**
 * Assertions on a {@code ContextSnapshot} (TEST-03, TEST-04). The failure messages of {@code contains}, {@code doesNotContain} and
 * {@code isEmpty} name keys, never values; inherited assertions print the snapshot's {@code toString()} (CORE-12).
 */
public final class SnapshotAssert extends AbstractAssert<SnapshotAssert, ContextSnapshot> {

    SnapshotAssert(ContextSnapshot actual) {
        super(actual, SnapshotAssert.class);
    }

    /**
     * Verifies that the snapshot binds the key to a value equal to {@code value}.
     *
     * @param key the key
     * @param value the expected value
     * @param <T> the key's type
     * @return this assertion
     * @throws NullPointerException if the key or the value is {@code null}
     */
    public <T> SnapshotAssert contains(ContextKey<T> key, T value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Optional<T> bound = actual.find(key);
        if (bound.isEmpty()) {
            failWithMessage("Expecting the snapshot to bind key %s, but it does not", key.name());
        } else if (!bound.get().equals(value)) {
            failWithMessage("Expecting the snapshot to bind key %s to the expected value, but it binds another value", key.name());
        }
        return this;
    }

    /**
     * Verifies that the snapshot does not bind the key.
     *
     * @param key the key
     * @return this assertion
     * @throws NullPointerException if the key is {@code null}
     */
    public SnapshotAssert doesNotContain(ContextKey<?> key) {
        Objects.requireNonNull(key, "key");
        if (actual.find(key).isPresent()) {
            failWithMessage("Expecting the snapshot not to bind key %s, but it does", key.name());
        }
        return this;
    }

    /**
     * Verifies that the snapshot binds no key.
     *
     * @return this assertion
     */
    public SnapshotAssert isEmpty() {
        if (!actual.isEmpty()) {
            failWithMessage("Expecting the snapshot to be empty, but it binds keys %s",
                    actual.keys().stream().map(ContextKey::name).sorted().toList());
        }
        return this;
    }
}
