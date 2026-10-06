package io.github.boundrail.context.test;

import io.github.boundrail.context.Violation;
import java.util.List;

/** The violations recorded for the running {@code @BoundrailTest} test method; resolvable as a test method parameter (TEST-02). */
public interface RecordedViolations {

    /**
     * Returns the violations reported in any application context since the running test method started, in reporting order.
     *
     * @return the violations
     * @throws IllegalStateException if no {@code @BoundrailTest} test method is running
     */
    List<Violation> all();
}
