package io.github.boundrail.context.test;

import io.github.boundrail.context.ContextSnapshot;
import java.util.Objects;

/** Entry points for the library's AssertJ-style assertions (TEST-03). */
public final class ContextAssertions {

    private ContextAssertions() {
    }

    /**
     * Starts assertions on a snapshot.
     *
     * @param snapshot the snapshot
     * @return the assertions on it
     * @throws NullPointerException if the snapshot is {@code null}
     */
    public static SnapshotAssert assertThat(ContextSnapshot snapshot) {
        return new SnapshotAssert(Objects.requireNonNull(snapshot, "snapshot"));
    }
}
