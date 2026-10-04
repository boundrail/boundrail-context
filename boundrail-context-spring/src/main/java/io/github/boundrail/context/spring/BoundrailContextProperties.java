package io.github.boundrail.context.spring;

import io.github.boundrail.context.Boundary;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 */
@ConfigurationProperties("boundrail.context")
public record BoundrailContextProperties(@DefaultValue Violations violations, @DefaultValue Inbound inbound,
        @DefaultValue Destinations destinations, @DefaultValue Wire wire, @DefaultValue Executors executors) {

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
     * @throws org.springframework.core.convert.ConversionFailedException if a mode is neither {@code attached} nor {@code detached}
     * @throws IllegalArgumentException if two keys are equal ignoring case and give different modes
     */
    public record Executors(@DefaultValue("true") boolean autoDecorate, @DefaultValue Map<String, String> modes) {

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
