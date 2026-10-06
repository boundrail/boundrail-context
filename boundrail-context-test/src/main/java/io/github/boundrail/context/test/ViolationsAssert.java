package io.github.boundrail.context.test;

import io.github.boundrail.context.Violation;
import java.util.List;
import java.util.Objects;
import org.assertj.core.api.AbstractAssert;

/**
 * Assertions on the violations recorded for the running {@code @BoundrailTest} test method (TEST-03, TEST-04). Failure messages name the
 * kinds and keys of the recorded violations.
 */
public final class ViolationsAssert extends AbstractAssert<ViolationsAssert, List<Violation>> {

    ViolationsAssert(List<Violation> actual) {
        super(actual, ViolationsAssert.class);
    }

    /**
     * Verifies that exactly {@code expected} violations were recorded.
     *
     * @param expected the expected number of violations
     * @return this assertion
     */
    public ViolationsAssert hasSize(int expected) {
        if (actual.size() != expected) {
            failWithMessage("Expecting %s violations, but %s were recorded: %s", expected, actual.size(), recorded());
        }
        return this;
    }

    /**
     * Verifies that at least one recorded violation has the kind.
     *
     * @param kind the kind
     * @return this assertion
     * @throws NullPointerException if the kind is {@code null}
     */
    public ViolationsAssert containsKind(Violation.Kind kind) {
        Objects.requireNonNull(kind, "kind");
        if (actual.stream().noneMatch(violation -> violation.kind() == kind)) {
            failWithMessage("Expecting a violation of kind %s, but the recorded violations are %s", kind, recorded());
        }
        return this;
    }

    /**
     * Verifies that no violation was recorded.
     *
     * @return this assertion
     */
    public ViolationsAssert isEmpty() {
        if (!actual.isEmpty()) {
            failWithMessage("Expecting no violations, but %s were recorded", recorded());
        }
        return this;
    }

    /** {@return the kind and key of each recorded violation, in reporting order} */
    private List<String> recorded() {
        return actual.stream().map(violation -> violation.kind() + " " + violation.key()).toList();
    }
}
