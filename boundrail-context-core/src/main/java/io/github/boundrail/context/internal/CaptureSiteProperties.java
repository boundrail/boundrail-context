package io.github.boundrail.context.internal;

import io.github.boundrail.context.ContextRuntime;

/**
 * Reads the initial capture-site mode from the system properties {@code boundrail.context.diagnostics.capture-sites} and
 * {@code boundrail.context.diagnostics.sample-rate} (RT-08). Unset properties give the defaults {@code off} and {@code 0.01}; any value other than
 * those RT-08 allows logs a warning and gives that property's default, as RT-04 does.
 */
public final class CaptureSiteProperties {

    static final String MODE_PROPERTY = "boundrail.context.diagnostics.capture-sites";
    static final String RATE_PROPERTY = "boundrail.context.diagnostics.sample-rate";

    private static final double DEFAULT_RATE = 0.01;

    private CaptureSiteProperties() {
    }

    /** {@return the mode the {@code capture-sites} system property selects} */
    public static ContextRuntime.CaptureSites mode() {
        return mode(System.getProperty(MODE_PROPERTY), System.getLogger("io.github.boundrail.context"));
    }

    /** {@return the sample rate the {@code sample-rate} system property selects} */
    public static double sampleRate() {
        return sampleRate(System.getProperty(RATE_PROPERTY), System.getLogger("io.github.boundrail.context"));
    }

    /**
     * Parses a value of the {@code capture-sites} property; {@code off}, {@code on} and {@code sampled} are compared exactly.
     *
     * @param value the property value, or {@code null} if unset
     * @param logger the logger that receives the warning for an unrecognised value
     * @return the mode, {@code OFF} if unset or unrecognised
     */
    static ContextRuntime.CaptureSites mode(String value, System.Logger logger) {
        if (value == null) {
            return ContextRuntime.CaptureSites.OFF;
        }
        return switch (value) {
            case "off" -> ContextRuntime.CaptureSites.OFF;
            case "on" -> ContextRuntime.CaptureSites.ON;
            case "sampled" -> ContextRuntime.CaptureSites.SAMPLED;
            default -> {
                logger.log(System.Logger.Level.WARNING, "Unrecognised value \"{0}\" for {1}; using off", value, MODE_PROPERTY);
                yield ContextRuntime.CaptureSites.OFF;
            }
        };
    }

    /**
     * Parses a value of the {@code sample-rate} property with {@link Double#parseDouble(String)}; the result must be from 0.0 to 1.0.
     *
     * @param value the property value, or {@code null} if unset
     * @param logger the logger that receives the warning for an unrecognised value
     * @return the sample rate, {@code 0.01} if unset or unrecognised
     */
    static double sampleRate(String value, System.Logger logger) {
        if (value == null) {
            return DEFAULT_RATE;
        }
        try {
            double rate = Double.parseDouble(value);
            if (rate >= 0.0 && rate <= 1.0) {
                return rate;
            }
        } catch (NumberFormatException e) {
            // logged below
        }
        logger.log(System.Logger.Level.WARNING, "Unrecognised value \"{0}\" for {1}; using {2}", value, RATE_PROPERTY, DEFAULT_RATE);
        return DEFAULT_RATE;
    }
}
