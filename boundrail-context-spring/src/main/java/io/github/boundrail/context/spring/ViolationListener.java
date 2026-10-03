package io.github.boundrail.context.spring;

import io.github.boundrail.context.Violation;

/**
 * Receives every violation the application context reports, in bean order, after it is counted and whether or not its log entry is
 * rate-limited (DIAG-11). An exception it throws is logged at WARN and does not change the outcome of the operation that reported the
 * violation.
 */
@FunctionalInterface
public interface ViolationListener {

    /**
     * Called for each reported violation.
     *
     * @param violation the violation
     */
    void onViolation(Violation violation);
}
