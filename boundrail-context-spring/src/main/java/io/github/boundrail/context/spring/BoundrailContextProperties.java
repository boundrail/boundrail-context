package io.github.boundrail.context.spring;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties under {@code boundrail.context}.
 *
 * @param violations how violations are reported
 * @param inbound which inbound callers are trusted
 * @param destinations how outbound destinations are classified
 * @param wire limits on wire values
 */
@ConfigurationProperties("boundrail.context")
public record BoundrailContextProperties(@DefaultValue Violations violations, @DefaultValue Inbound inbound,
        @DefaultValue Destinations destinations, @DefaultValue Wire wire) {

    /**
     * Violation handling (DIAG-02) and reporting (DIAG-03).
     *
     * @param mode whether a {@code MISSING_REQUIRED} violation fails the operation or is only reported
     * @param logInterval the rate limit for violation log entries: one per kind, key and boundary per interval; zero logs every
     *     occurrence
     * @throws IllegalArgumentException if {@code logInterval} is negative
     */
    public record Violations(@DefaultValue("fail") Mode mode, @DefaultValue("1m") Duration logInterval) {

        /** What happens on a {@code MISSING_REQUIRED} violation (DIAG-02). */
        public enum Mode {
            /** Report the violation, then throw a {@code ContextViolationException} carrying it. */
            FAIL,
            /** Report the violation and continue without the key. */
            REPORT
        }

        public Violations {
            if (logInterval.isNegative()) {
                throw new IllegalArgumentException("boundrail.context.violations.log-interval must not be negative: " + logInterval);
            }
        }
    }

    /**
     * Inbound trust (IN-03).
     *
     * @param trust which built-in verifier decides trust; an {@code InboundTrustVerifier} bean overrides it
     */
    public record Inbound(@DefaultValue("none") Trust trust) {

        /** The built-in verifier (IN-03). */
        public enum Trust {
            /** Trust no caller. */
            NONE,
            /** Trust callers whose remote address is inside a trusted CIDR. */
            CIDR
        }
    }

    /**
     * Host patterns of the default {@code HostPatternClassifier} (DEST-02, OUT-03).
     *
     * @param internalHosts host patterns classified as internal
     * @param externalHosts host patterns classified as external
     */
    public record Destinations(@DefaultValue List<String> internalHosts, @DefaultValue List<String> externalHosts) {
    }

    /**
     * Wire values (WIRE-04).
     *
     * @param maxValueLength the maximum length of an encoded wire value, in UTF-16 code units ({@code String.length()})
     * @throws IllegalArgumentException if {@code maxValueLength} is less than 1
     */
    public record Wire(@DefaultValue("256") int maxValueLength) {

        public Wire {
            if (maxValueLength < 1) {
                throw new IllegalArgumentException("boundrail.context.wire.max-value-length must be at least 1: " + maxValueLength);
            }
        }
    }
}
