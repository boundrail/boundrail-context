package io.github.boundrail.context.spring;

import static io.github.boundrail.context.ContextRuntime.CaptureSites.OFF;
import static io.github.boundrail.context.ContextRuntime.CaptureSites.ON;
import static io.github.boundrail.context.ContextRuntime.CaptureSites.SAMPLED;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.ContextRuntime;
import io.github.boundrail.context.ContextRuntime.CaptureSites;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

/** The Spring module applies the capture-site properties that are set, JVM-wide (DIAG-05). Each test restores the JVM-wide mode. */
class CaptureSiteSettingsTest {

    private static final String CAPTURE_SITES = "boundrail.context.diagnostics.capture-sites";
    private static final String SAMPLE_RATE = "boundrail.context.diagnostics.sample-rate";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class));

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

    /** The JVM-wide mode and rate, as {@code <mode> <rate>}. */
    private static String current() {
        return ContextRuntime.captureSites() + " " + ContextRuntime.sampleRate();
    }

    /** Starts a context with these properties as the first property source, so values may be blank, and returns the mode afterwards. */
    private String afterContextWith(Map<String, Object> properties) {
        StringBuilder started = new StringBuilder();
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    started.append(current());
                });
        return started.toString();
    }

    @Test
    @DisplayName("DIAG-05 capture-sites set in the environment is applied and an unset sample-rate keeps the current rate")
    void captureSitesSetIsAppliedAndAnUnsetSampleRateKeepsTheCurrentRate() {
        ContextRuntime.captureSites(OFF, 0.25);

        assertThat(afterContextWith(Map.of(CAPTURE_SITES, "on"))).isEqualTo("ON 0.25");
    }

    @Test
    @DisplayName("DIAG-05 sample-rate set alone keeps the current mode")
    void sampleRateSetAloneKeepsTheCurrentMode() {
        ContextRuntime.captureSites(SAMPLED, 0.25);

        assertThat(afterContextWith(Map.of(SAMPLE_RATE, "0.75"))).isEqualTo("SAMPLED 0.75");
    }

    @Test
    @DisplayName("DIAG-05 capture-sites is matched with relaxed enum binding and a blank value means off")
    void captureSitesIsMatchedWithRelaxedEnumBindingAndABlankValueMeansOff() {
        Map<String, String> after = new LinkedHashMap<>();
        for (String value : List.of("ON", " Sampled ", "")) {
            ContextRuntime.captureSites(ON, 0.5);
            after.put("'" + value + "'", afterContextWith(Map.of(CAPTURE_SITES, value)));
        }

        assertThat(after).containsExactly(
                Map.entry("'ON'", "ON 0.5"),
                Map.entry("' Sampled '", "SAMPLED 0.5"),
                Map.entry("''", "OFF 0.5"));
    }

    @ParameterizedTest(name = "{0}={1}, lazy initialization: {2}")
    @CsvSource({
        "capture-sites, bogus, false", "capture-sites, bogus, true",
        "sample-rate, NaN, false", "sample-rate, NaN, true",
        "sample-rate, -0.1, false", "sample-rate, -0.1, true",
        "sample-rate, 1.5, false", "sample-rate, 1.5, true",
        "sample-rate, abc, false", "sample-rate, abc, true"})
    @DisplayName("DIAG-05 an invalid capture-sites or sample-rate fails startup, also with lazy initialization")
    void anInvalidCaptureSitesOrSampleRateFailsStartup(String property, String value, boolean lazy) {
        ApplicationContextRunner configured = runner.withPropertyValues("boundrail.context.diagnostics." + property + "=" + value);
        if (lazy) {
            configured = configured.withPropertyValues("spring.main.lazy-initialization=true")
                    .withInitializer(context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));
        }

        configured.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class).hasMessageContaining(value);
        });
    }

    @Test
    @DisplayName("DIAG-05 a context that sets neither property leaves the JVM-wide mode unchanged")
    void aContextThatSetsNeitherPropertyLeavesTheJvmWideModeUnchanged() {
        ContextRuntime.captureSites(ON, 0.5);

        assertThat(afterContextWith(Map.of())).isEqualTo("ON 0.5");
    }

    @Test
    @DisplayName("DIAG-05 a blank sample-rate counts as not set")
    void aBlankSampleRateCountsAsNotSet() {
        ContextRuntime.captureSites(OFF, 0.5);
        String withCaptureSites = afterContextWith(Map.of(CAPTURE_SITES, "on", SAMPLE_RATE, ""));
        ContextRuntime.captureSites(OFF, 0.5);
        String alone = afterContextWith(Map.of(SAMPLE_RATE, " "));

        assertThat(withCaptureSites).as("capture-sites=on, sample-rate blank").isEqualTo("ON 0.5");
        assertThat(alone).as("sample-rate blank alone").isEqualTo("OFF 0.5");
    }
}
