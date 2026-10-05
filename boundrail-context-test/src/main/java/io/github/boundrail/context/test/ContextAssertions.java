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

    /**
     * Starts assertions on the violations recorded so far for the running {@code @BoundrailTest} test method (TEST-02).
     *
     * @return the assertions on them
     * @throws IllegalStateException if no {@code @BoundrailTest} test method is running
     */
    public static ViolationsAssert assertThatViolations() {
        return new ViolationsAssert(ViolationRecorder.all());
    }
}
