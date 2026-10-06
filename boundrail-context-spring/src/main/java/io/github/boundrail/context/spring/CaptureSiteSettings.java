package io.github.boundrail.context.spring;

import io.github.boundrail.context.ContextRuntime;
import io.github.boundrail.context.ContextRuntime.CaptureSites;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.env.Environment;

/**
 * Applies {@code boundrail.context.diagnostics.capture-sites} and {@code sample-rate} through {@link ContextRuntime#captureSites}, JVM-wide,
 * when the context starts (DIAG-05). Only the properties that are set are applied; the other keeps its current value, and a context that
 * sets neither changes nothing. A blank {@code capture-sites} means {@code off}; a blank {@code sample-rate} counts as not set. An invalid
 * value fails startup.
 */
final class CaptureSiteSettings {

    private static final String CAPTURE_SITES = "boundrail.context.diagnostics.capture-sites";
    private static final String SAMPLE_RATE = "boundrail.context.diagnostics.sample-rate";

    /**
     * Reads the properties and applies those that are set.
     *
     * @throws org.springframework.core.convert.ConversionFailedException if a value cannot be converted
     * @throws IllegalArgumentException if the sample rate is NaN or outside 0.0 – 1.0
     */
    CaptureSiteSettings(Environment environment) {
        Binder binder = Binder.get(environment);
        String mode = binder.bind(CAPTURE_SITES, String.class).orElse(null);
        String rate = binder.bind(SAMPLE_RATE, String.class).map(value -> value.isBlank() ? null : value).orElse(null);
        if (mode == null && rate == null) {
            return;
        }
        CaptureSites newMode = mode == null ? ContextRuntime.captureSites()
                : mode.isBlank() ? CaptureSites.OFF
                : ApplicationConversionService.getSharedInstance().convert(mode, CaptureSites.class);
        double newRate = rate == null ? ContextRuntime.sampleRate()
                : ApplicationConversionService.getSharedInstance().convert(rate.strip(), Double.class);
        ContextRuntime.captureSites(newMode, newRate);
    }
}
