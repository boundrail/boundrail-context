package io.github.boundrail.context.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.ContextRuntime.CaptureSites;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CaptureSitePropertiesTest {

    /** A logger that records the level of every message it receives. */
    private static final class RecordingLogger implements System.Logger {

        final List<Level> levels = new ArrayList<>();

        @Override
        public String getName() {
            return "recording";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            levels.add(level);
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            levels.add(level);
        }
    }

    private final RecordingLogger logger = new RecordingLogger();

    @Test
    @DisplayName("RT-08 off, on and sampled are recognised exactly")
    void offOnAndSampledAreRecognisedExactly() {
        assertThat(CaptureSiteProperties.mode("off", logger)).isEqualTo(CaptureSites.OFF);
        assertThat(CaptureSiteProperties.mode("on", logger)).isEqualTo(CaptureSites.ON);
        assertThat(CaptureSiteProperties.mode("sampled", logger)).isEqualTo(CaptureSites.SAMPLED);
        assertThat(CaptureSiteProperties.mode(null, logger)).as("unset").isEqualTo(CaptureSites.OFF);
        assertThat(logger.levels).isEmpty();

        assertThat(CaptureSiteProperties.mode("ON", logger)).isEqualTo(CaptureSites.OFF);
        assertThat(CaptureSiteProperties.mode(" on", logger)).isEqualTo(CaptureSites.OFF);
        assertThat(logger.levels).containsExactly(Level.WARNING, Level.WARNING);
    }

    @Test
    @DisplayName("RT-08 a sample rate is parsed with Double.parseDouble and accepted from 0.0 to 1.0")
    void aSampleRateIsParsedWithDoubleParseDoubleAndAcceptedFrom0To1() {
        assertThat(CaptureSiteProperties.sampleRate("0", logger)).isEqualTo(0.0);
        assertThat(CaptureSiteProperties.sampleRate("1.0", logger)).isEqualTo(1.0);
        assertThat(CaptureSiteProperties.sampleRate("0.25", logger)).isEqualTo(0.25);
        assertThat(CaptureSiteProperties.sampleRate(" 0.5 ", logger)).isEqualTo(0.5);
        assertThat(CaptureSiteProperties.sampleRate("5e-1", logger)).isEqualTo(0.5);
        assertThat(CaptureSiteProperties.sampleRate("0x1p-1", logger)).isEqualTo(0.5);
        assertThat(CaptureSiteProperties.sampleRate(null, logger)).as("unset").isEqualTo(0.01);
        assertThat(logger.levels).isEmpty();

        for (String invalid : List.of("abc", "NaN", "-0.1", "2")) {
            assertThat(CaptureSiteProperties.sampleRate(invalid, logger)).as(invalid).isEqualTo(0.01);
        }
        assertThat(logger.levels).containsExactly(Level.WARNING, Level.WARNING, Level.WARNING, Level.WARNING);
    }

    @Test
    @DisplayName("RT-08 the mode and sample rate are read from the boundrail.context.diagnostics.capture-sites and sample-rate system properties")
    void theModeAndSampleRateAreReadFromTheSystemProperties() {
        String previousMode = System.getProperty("boundrail.context.diagnostics.capture-sites");
        String previousRate = System.getProperty("boundrail.context.diagnostics.sample-rate");
        try {
            System.setProperty("boundrail.context.diagnostics.capture-sites", "sampled");
            System.setProperty("boundrail.context.diagnostics.sample-rate", "0.25");

            assertThat(CaptureSiteProperties.mode()).isEqualTo(CaptureSites.SAMPLED);
            assertThat(CaptureSiteProperties.sampleRate()).isEqualTo(0.25);
        } finally {
            restore("boundrail.context.diagnostics.capture-sites", previousMode);
            restore("boundrail.context.diagnostics.sample-rate", previousRate);
        }
    }

    private static void restore(String property, String previous) {
        if (previous == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, previous);
        }
    }
}
