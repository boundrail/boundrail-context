package io.github.boundrail.context.spring;

import io.github.boundrail.context.Boundary;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.ApplicationConversionService;

/**
 * Configuration properties under {@code boundrail.context}.
 *
 * @param violations how violations are reported
 * @param inbound which inbound callers are trusted
 * @param destinations how outbound destinations are classified
 * @param wire limits on wire values
 * @param executors how Spring's task executors are decorated
 * @param audit the startup executor audit
 * @param deadline inbound deadlines and their expiry
 */
@ConfigurationProperties("boundrail.context")
public record BoundrailContextProperties(@DefaultValue Violations violations, @DefaultValue Inbound inbound,
        @DefaultValue Destinations destinations, @DefaultValue Wire wire, @DefaultValue Executors executors, @DefaultValue Audit audit,
        @DefaultValue Deadline deadline) {

    /**
     * Violation handling (DIAG-02) and reporting (DIAG-03).
     *
     * @param mode whether a {@code MISSING_REQUIRED} violation fails the operation or is only reported
     * @param logInterval the rate limit for violation log entries: one per kind, key and boundary per interval; zero logs every
     *     occurrence
     */
    public record Violations(@DefaultValue("fail") Mode mode, @DefaultValue("1m") Duration logInterval) {

        /** What happens on a {@code MISSING_REQUIRED} violation (DIAG-02). */
        public enum Mode {
            /** Report the violation, then throw a {@code ContextViolationException} carrying it. */
            FAIL,
            /** Report the violation and continue without the key. */
            REPORT
        }

        /**
         * Validates the values.
         *
         * @throws IllegalArgumentException if {@code logInterval} is negative
         */
        public Violations {
            if (logInterval.isNegative()) {
                throw new IllegalArgumentException("boundrail.context.violations.log-interval must not be negative: " + logInterval);
            }
        }
    }

    /**
     * Inbound trust (IN-03, IN-08).
     *
     * @param trust which built-in verifier decides trust; an {@code InboundTrustVerifier} bean overrides it
     * @param trustedCidrs the IPv4 and IPv6 CIDRs whose remote addresses are trusted when {@code trust} is {@code cidr}
     */
    public record Inbound(@DefaultValue("none") Trust trust, @DefaultValue List<String> trustedCidrs) {

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
     * Executors (EXE-01, EXE-03). The decorating post-processor runs before this bean exists, so it binds the same properties through
     * {@code Binder}.
     *
     * @param autoDecorate whether every {@code ThreadPoolTaskExecutor}, {@code SimpleAsyncTaskExecutor} and {@code ThreadPoolTaskScheduler}
     *     bean is decorated so that its tasks run in the submitter's context
     * @param modes the mode of each decorated executor, by bean name matched ignoring case: {@code attached} or {@code detached}, matched
     *     with relaxed enum binding; empty or blank means {@code detached}, and so does a bean name with no entry
     */
    public record Executors(@DefaultValue("true") boolean autoDecorate, @DefaultValue Map<String, String> modes) {

        /**
         * Converts and checks the modes.
         *
         * @throws org.springframework.core.convert.ConversionFailedException if a mode is neither {@code attached} nor {@code detached}
         * @throws IllegalArgumentException if two keys are equal ignoring case and give different modes
         */
        public Executors {
            modes = Map.copyOf(modes);
            Map<String, Boundary> converted = new HashMap<>();
            modes.forEach((name, mode) -> converted.put(name, boundary(mode)));
            converted.forEach((name, mode) -> converted.forEach((other, otherMode) -> {
                if (name.compareTo(other) < 0 && name.equalsIgnoreCase(other) && mode != otherMode) {
                    throw new IllegalArgumentException("boundrail.context.executors.modes has the keys " + name + " and " + other
                            + ", which name the same bean, with different modes");
                }
            }));
        }

        /** {@return the mode of the executor bean, its name matched ignoring case; {@code DETACHED} if it has none} */
        Boundary mode(String beanName) {
            return modes.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(beanName)).findFirst()
                    .map(entry -> boundary(entry.getValue())).orElse(Boundary.DETACHED);
        }

        private static Boundary boundary(String mode) {
            if (mode.isBlank()) {
                return Boundary.DETACHED;
            }
            return switch (ApplicationConversionService.getSharedInstance().convert(mode, Mode.class)) {
                case ATTACHED -> Boundary.ATTACHED;
                case DETACHED -> Boundary.DETACHED;
            };
        }

        /** The values of {@code modes}. */
        private enum Mode { ATTACHED, DETACHED }
    }

    /**
     * The startup executor audit (DIAG-06). Executor evidence (DIAG-04) does not depend on it.
     *
     * @param enabled whether executor beans are audited when the application is ready
     * @param failOnGap whether startup fails when the audit finds a gap
     */
    public record Audit(@DefaultValue("true") boolean enabled, @DefaultValue("false") boolean failOnGap) {
    }

    /**
     * Wire values (WIRE-04).
     *
     * @param maxValueLength the maximum length of an encoded wire value, in UTF-16 code units ({@code String.length()})
     */
    public record Wire(@DefaultValue("256") int maxValueLength) {

        /**
         * Validates the value.
         *
         * @throws IllegalArgumentException if {@code maxValueLength} is less than 1
         */
        public Wire {
            if (maxValueLength < 1) {
                throw new IllegalArgumentException("boundrail.context.wire.max-value-length must be at least 1: " + maxValueLength);
            }
        }
    }

    /**
     * Deadlines (DL-03, DL-04, DL-08). The durations are bound as strings, because Spring Boot fails on a duration that is only
     * whitespace, and converted with {@code ApplicationConversionService}, which accepts the same formats as Boot's duration binding; the
     * library reads them through {@code defaultInboundDuration()} and {@code maxInboundDuration()}.
     *
     * @param defaultInbound the budget bound when no trusted deadline is present; {@code null} when not set, which a blank value also means
     * @param maxInbound the cap on trusted inbound deadlines; blank means {@code 30s}
     * @param expiredStatus the status of a request whose deadline has expired, from 400 to 599
     * @param mapException whether an escaping {@code DeadlineExceededException} is mapped to {@code expiredStatus}
     */
    public record Deadline(String defaultInbound, @DefaultValue("30s") String maxInbound, @DefaultValue("504") int expiredStatus,
            @DefaultValue("true") boolean mapException) {

        /**
         * Converts and checks the values.
         *
         * @throws org.springframework.core.convert.ConversionFailedException if a duration cannot be converted
         * @throws IllegalArgumentException if a duration is zero or negative, or {@code expiredStatus} is outside 400 – 599
         */
        public Deadline {
            if (defaultInbound != null && defaultInbound.isBlank()) {
                defaultInbound = null;
            }
            if (maxInbound == null || maxInbound.isBlank()) {
                maxInbound = "30s";
            }
            duration(defaultInbound).ifPresent(duration -> requirePositive("default-inbound", duration));
            requirePositive("max-inbound", duration(maxInbound).orElseThrow());
            if (expiredStatus < 400 || expiredStatus > 599) {
                throw new IllegalArgumentException("boundrail.context.deadline.expired-status must be from 400 to 599: " + expiredStatus);
            }
        }

        /** {@return the budget bound when no trusted deadline is present, if set} */
        Optional<Duration> defaultInboundDuration() {
            return duration(defaultInbound);
        }

        /** {@return the cap on trusted inbound deadlines} */
        Duration maxInboundDuration() {
            return duration(maxInbound).orElseThrow();
        }

        private static Optional<Duration> duration(String value) {
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(ApplicationConversionService.getSharedInstance().convert(value, Duration.class));
        }

        private static void requirePositive(String property, Duration value) {
            if (value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("boundrail.context.deadline." + property + " must be greater than zero: " + value);
            }
        }
    }
}
