package io.github.boundrail.context.spring.internal;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.Violation;

/**
 * What the Kafka adapters need from the Spring module: the effective policy, the violation settings and the context's violation reporting
 * (DESIGN D70, D78). Not part of any contract; CORE-03 does not cover it.
 */
public interface AdapterSupport {

    /** {@return the effective policy: the built-in policy merged with every {@code ContextPolicy} bean (POL-07)} */
    ContextPolicy policy();

    /** {@return {@code true} if {@code violations.mode} is {@code fail}} */
    boolean failOnMissingRequired();

    /** {@return {@code wire.max-value-length}} */
    int maxValueLength();

    /** {@return a violation with the observed evidence of the current thread and no destination (DIAG-04)} */
    Violation violation(Violation.Kind kind, String key, Boundary boundary);

    /** Logs and counts the violation, and passes it to the context's {@code ViolationListener} beans (DIAG-03, DIAG-11). */
    void report(Violation violation);
}
