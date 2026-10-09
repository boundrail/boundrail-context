package io.github.boundrail.context.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.env.MapPropertySource;

/** Binding and startup validation of the {@code boundrail.context.deadline.*} properties (DL-03, DL-04, DL-08). */
class DeadlinePropertiesTest {

    private static final String PREFIX = "boundrail.context.deadline.";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class));

    /** Adds the properties as the first property source, so values keep their spaces and may be empty. */
    private static ApplicationContextRunner withProperties(ApplicationContextRunner runner, Map<String, Object> properties) {
        return runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("test", properties)));
    }

    /** Lazy initialization as {@code SpringApplication} sets it up for {@code spring.main.lazy-initialization=true}. */
    private static ApplicationContextRunner lazy(ApplicationContextRunner runner) {
        return runner.withPropertyValues("spring.main.lazy-initialization=true")
                .withInitializer(context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));
    }

    /** A runner with the one property set, optionally with lazy initialization. */
    private ApplicationContextRunner configured(String property, String value, boolean lazy) {
        ApplicationContextRunner configured = withProperties(runner, Map.of(PREFIX + property, value));
        return lazy ? lazy(configured) : configured;
    }

    /** Asserts that startup fails because the library rejected the converted value, naming the property. */
    private void assertStartupRejects(String property, String value, boolean lazy) {
        configured(property, value, lazy).run(context -> {
            assertThat(context).as("%s=%s, lazy=%s", property, value, lazy).hasFailed();
            assertThat(context.getStartupFailure()).as("%s=%s, lazy=%s", property, value, lazy).rootCause()
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(PREFIX + property);
        });
    }

    /** Asserts that startup fails because the value cannot be converted. */
    private void assertStartupFailsToConvert(String property, String value, boolean lazy) {
        configured(property, value, lazy).run(context -> {
            assertThat(context).as("%s=%s, lazy=%s", property, value, lazy).hasFailed();
            List<Throwable> chain = new ArrayList<>();
            for (Throwable cause = context.getStartupFailure(); cause != null; cause = cause.getCause()) {
                chain.add(cause);
            }
            assertThat(chain).as("%s=%s, lazy=%s", property, value, lazy).hasAtLeastOneElementOfType(ConversionFailedException.class);
        });
    }

    /** Runs with the one property set, optionally with lazy initialization, and returns the bound settings, asserting that startup succeeds. */
    private BoundrailContextProperties.Deadline bound(String property, String value, boolean lazy) {
        BoundrailContextProperties.Deadline[] deadline = new BoundrailContextProperties.Deadline[1];
        configured(property, value, lazy).run(context -> {
            assertThat(context).as("%s=%s, lazy=%s", property, value, lazy).hasNotFailed();
            deadline[0] = context.getBean(BoundrailContextProperties.class).deadline();
        });
        return deadline[0];
    }

    private BoundrailContextProperties.Deadline bound(String property, String value) {
        return bound(property, value, false);
    }

    @Test
    @DisplayName("DL-03 deadline properties default to no default-inbound, a max-inbound of 30s, expired-status 504 and map-exception true")
    void deadlinePropertiesHaveTheirDefaults() {
        runner.run(context -> {
            BoundrailContextProperties.Deadline deadline = context.getBean(BoundrailContextProperties.class).deadline();

            assertThat(deadline.defaultInboundDuration()).isEmpty();
            assertThat(deadline.maxInboundDuration()).isEqualTo(Duration.ofSeconds(30));
            assertThat(deadline.expiredStatus()).isEqualTo(504);
            assertThat(deadline.mapException()).isTrue();
        });
    }

    @Test
    @DisplayName("DL-03 default-inbound and max-inbound are bound as Durations with Spring Boot's binding")
    void defaultInboundAndMaxInboundAreBoundAsDurations() {
        Map<String, Duration> expected = Map.of("250ms", Duration.ofMillis(250), "PT2S", Duration.ofSeconds(2), "5", Duration.ofMillis(5));

        expected.forEach((value, duration) -> {
            assertThat(bound("default-inbound", value).defaultInboundDuration()).as("default-inbound=%s", value).contains(duration);
            assertThat(bound("max-inbound", value).maxInboundDuration()).as("max-inbound=%s", value).isEqualTo(duration);
        });
    }

    @Test
    @DisplayName("DL-03 a blank default-inbound counts as not set, and a blank max-inbound is the default 30s")
    void aBlankDefaultInboundIsNotSetAndABlankMaxInboundIs30s() {
        for (String blank : new String[] {"", "   "}) {
            BoundrailContextProperties.Deadline blankDefault = bound("default-inbound", blank);
            assertThat(blankDefault.defaultInboundDuration()).as("default-inbound='%s'", blank).isEmpty();
            assertThat(blankDefault.defaultInbound()).as("default-inbound='%s'", blank).isNull();
            assertThat(bound("max-inbound", blank).maxInboundDuration()).as("max-inbound='%s'", blank).isEqualTo(Duration.ofSeconds(30));
        }
    }

    @Test
    @DisplayName("DL-03 default-inbound may be greater than max-inbound")
    void defaultInboundMayBeGreaterThanMaxInbound() {
        withProperties(runner, Map.of(PREFIX + "default-inbound", "60s", PREFIX + "max-inbound", "30s")).run(context -> {
            assertThat(context).hasNotFailed();
            BoundrailContextProperties.Deadline deadline = context.getBean(BoundrailContextProperties.class).deadline();

            assertThat(deadline.defaultInboundDuration()).contains(Duration.ofSeconds(60));
            assertThat(deadline.maxInboundDuration()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    static Stream<Arguments> invalidDurations() {
        return Stream.of("default-inbound", "max-inbound").flatMap(property -> Stream.of("0", "-1s", "abc")
                .flatMap(value -> Stream.of(false, true).map(lazy -> Arguments.of(property, value, lazy))));
    }

    /** Whether the value is a duration the library must reject, rather than one that cannot be converted. */
    private static boolean converts(String value) {
        return !value.equals("abc");
    }

    @ParameterizedTest(name = "{0}={1}, lazy initialization: {2}")
    @MethodSource("invalidDurations")
    @DisplayName("DL-03 a default-inbound or max-inbound that is zero, negative or not a duration fails startup, also with lazy initialization")
    void aZeroNegativeOrInvalidDurationFailsStartup(String property, String value, boolean lazy) {
        if (converts(value)) {
            assertStartupRejects(property, value, lazy);
        } else {
            assertStartupFailsToConvert(property, value, lazy);
        }
    }

    @ParameterizedTest(name = "lazy initialization: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("DL-04 expired-status is from 400 to 599, and any other value or a non-integer fails startup, also with lazy initialization")
    void expiredStatusIsFrom400To599(boolean lazy) {
        assertThat(bound("expired-status", "400", lazy).expiredStatus()).isEqualTo(400);
        assertThat(bound("expired-status", "599", lazy).expiredStatus()).isEqualTo(599);

        assertStartupRejects("expired-status", "399", lazy);
        assertStartupRejects("expired-status", "600", lazy);
        assertStartupFailsToConvert("expired-status", "abc", lazy);
    }

    @ParameterizedTest(name = "lazy initialization: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("DL-08 map-exception is bound as a Boot boolean, and a value Boot cannot convert fails startup, also with lazy initialization")
    void mapExceptionIsBoundAsABootBoolean(boolean lazy) {
        for (String yes : new String[] {"yes", "ON", "1"}) {
            assertThat(bound("map-exception", yes, lazy).mapException()).as("map-exception=%s", yes).isTrue();
        }
        for (String no : new String[] {"no", "off", "0"}) {
            assertThat(bound("map-exception", no, lazy).mapException()).as("map-exception=%s", no).isFalse();
        }

        assertStartupFailsToConvert("map-exception", "maybe", lazy);
    }
}
