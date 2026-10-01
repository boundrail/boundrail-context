package io.github.boundrail.context.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties under {@code boundrail.context}.
 *
 * @param violations how violations are reported
 */
@ConfigurationProperties("boundrail.context")
public record BoundrailContextProperties(@DefaultValue Violations violations) {

    /**
     * Violation reporting (DIAG-03).
     *
     * @param logInterval the rate limit for violation log entries: one per kind, key and boundary per interval; zero logs every
     *     occurrence
     * @throws IllegalArgumentException if {@code logInterval} is negative
     */
    public record Violations(@DefaultValue("1m") Duration logInterval) {

        public Violations {
            if (logInterval.isNegative()) {
                throw new IllegalArgumentException("boundrail.context.violations.log-interval must not be negative: " + logInterval);
            }
        }
    }
}
