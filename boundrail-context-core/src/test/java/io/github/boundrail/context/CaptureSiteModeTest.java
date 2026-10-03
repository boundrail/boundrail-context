package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.boundrail.context.ContextRuntime.CaptureSites;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CaptureSiteModeTest {

    private CaptureSites savedMode;
    private double savedRate;

    @BeforeEach
    void saveMode() {
        savedMode = ContextRuntime.captureSites();
        savedRate = ContextRuntime.sampleRate();
    }

    @AfterEach
    void restoreMode() {
        ContextRuntime.captureSites(savedMode, savedRate);
    }

    @Test
    @DisplayName("RT-08 the mode defaults to off with a sample rate of 0.01")
    void theModeDefaultsToOffWithASampleRateOf001() {
        assertThat(ContextRuntime.captureSites()).isEqualTo(CaptureSites.OFF);
        assertThat(ContextRuntime.sampleRate()).isEqualTo(0.01);
    }

    @Test
    @DisplayName("RT-08 captureSites(mode, rate) changes the mode and the rate, read back by captureSites() and sampleRate()")
    void captureSitesChangesTheModeAndTheRate() {
        ContextRuntime.captureSites(CaptureSites.SAMPLED, 0.25);

        assertThat(ContextRuntime.captureSites()).isEqualTo(CaptureSites.SAMPLED);
        assertThat(ContextRuntime.sampleRate()).isEqualTo(0.25);

        ContextRuntime.captureSites(CaptureSites.ON, 0.75);

        assertThat(ContextRuntime.captureSites()).isEqualTo(CaptureSites.ON);
        assertThat(ContextRuntime.sampleRate()).isEqualTo(0.75);
    }

    @Test
    @DisplayName("RT-08 a NaN or out-of-range sample rate throws IllegalArgumentException and leaves the mode unchanged")
    void aNaNOrOutOfRangeSampleRateThrowsAndLeavesTheModeUnchanged() {
        ContextRuntime.captureSites(CaptureSites.SAMPLED, 0.25);

        for (double rate : new double[] {Double.NaN, -0.1, 1.1, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThatIllegalArgumentException().as("rate %s", rate).isThrownBy(() -> ContextRuntime.captureSites(CaptureSites.ON, rate));
            assertThat(ContextRuntime.captureSites()).as("mode after rate %s", rate).isEqualTo(CaptureSites.SAMPLED);
            assertThat(ContextRuntime.sampleRate()).as("rate after rate %s", rate).isEqualTo(0.25);
        }

        ContextRuntime.captureSites(CaptureSites.ON, 0.0);
        assertThat(ContextRuntime.sampleRate()).isEqualTo(0.0);
        ContextRuntime.captureSites(CaptureSites.ON, 1.0);
        assertThat(ContextRuntime.sampleRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("CORE-14 captureSites(null, rate) throws NullPointerException")
    void captureSitesRejectsANullMode() {
        ContextRuntime.captureSites(CaptureSites.SAMPLED, 0.25);

        assertThatNullPointerException().isThrownBy(() -> ContextRuntime.captureSites(null, 0.5));
        assertThatNullPointerException().isThrownBy(() -> ContextRuntime.captureSites(null, Double.NaN));
        assertThat(ContextRuntime.captureSites()).isEqualTo(CaptureSites.SAMPLED);
        assertThat(ContextRuntime.sampleRate()).isEqualTo(0.25);
    }
}
