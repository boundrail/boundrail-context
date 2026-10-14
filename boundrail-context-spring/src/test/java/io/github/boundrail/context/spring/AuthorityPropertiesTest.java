package io.github.boundrail.context.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.spring.BoundrailContextProperties.Authority.Propagation;
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
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

/** Binding of the {@code boundrail.context.authority.*} properties (ID-04, ID-06, ID-11). */
class AuthorityPropertiesTest {

    private static final String PREFIX = "boundrail.context.authority.";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class));

    /** Adds the properties as the first property source, so values keep their spaces and may be empty. */
    private static ApplicationContextRunner withProperties(ApplicationContextRunner runner, Map<String, Object> properties) {
        return runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("test", properties)));
    }

    /** Runs with the properties and returns the bound properties, asserting that startup succeeds. */
    private static BoundrailContextProperties bound(ApplicationContextRunner runner) {
        BoundrailContextProperties[] properties = new BoundrailContextProperties[1];
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            properties[0] = context.getBean(BoundrailContextProperties.class);
        });
        return properties[0];
    }

    /** Lazy initialization as {@code SpringApplication} sets it up for {@code spring.main.lazy-initialization=true}. */
    private static ApplicationContextRunner lazy(ApplicationContextRunner runner) {
        return runner.withPropertyValues("spring.main.lazy-initialization=true")
                .withInitializer(context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));
    }

    @ParameterizedTest(name = "lazy initialization: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("ID-04 an authority.propagation that is neither forward nor exchange fails startup, also with lazy initialization")
    void anInvalidPropagationFailsStartup(boolean lazy) {
        ApplicationContextRunner configured = runner.withPropertyValues(PREFIX + "propagation=bogus");
        (lazy ? lazy(configured) : configured).run(context -> {
            assertThat(context).hasFailed();
            List<String> boundNames = new ArrayList<>();
            for (Throwable cause = context.getStartupFailure(); cause != null; cause = cause.getCause()) {
                if (cause instanceof BindException bind) {
                    boundNames.add(bind.getName().toString());
                }
            }
            assertThat(boundNames).contains(PREFIX + "propagation");
        });
    }

    @Test
    @DisplayName("ID-04 authority.propagation defaults to forward and the exchange properties to unset")
    void propagationDefaultsToForwardAndExchangeToUnset() {
        BoundrailContextProperties.Authority authority = bound(runner).authority();

        assertThat(authority.propagation()).isEqualTo(Propagation.FORWARD);
        assertThat(authority.exchange().tokenUri()).isNull();
        assertThat(authority.exchange().clientId()).isNull();
        assertThat(authority.exchange().clientSecret()).isNull();
    }

    static Stream<Arguments> propagationValues() {
        return Stream.of(Arguments.of("exchange", Propagation.EXCHANGE), Arguments.of("EXCHANGE", Propagation.EXCHANGE),
                Arguments.of(" Exchange ", Propagation.EXCHANGE), Arguments.of("", Propagation.FORWARD),
                Arguments.of("   ", Propagation.FORWARD));
    }

    @ParameterizedTest(name = "propagation={0}")
    @MethodSource("propagationValues")
    @DisplayName("ID-04 authority.propagation is matched with relaxed enum binding, and an empty or blank value means forward")
    void propagationIsMatchedWithRelaxedEnumBinding(String value, Propagation expected) {
        // Valid exchange settings, so that a value selecting the built-in exchange propagator starts (ID-11).
        BoundrailContextProperties properties = bound(withProperties(runner, Map.of(PREFIX + "propagation", value,
                PREFIX + "exchange.token-uri", "http://token-exchange.svc.test/token", PREFIX + "exchange.client-id", "edge",
                PREFIX + "exchange.client-secret", "s3cr3t")));

        assertThat(properties.authority().propagation()).isEqualTo(expected);
    }

    @Test
    @DisplayName("ID-11 the exchange properties are bound as given and not validated by binding")
    void exchangePropertiesAreBoundAsGiven() {
        BoundrailContextProperties.Authority.Exchange exchange = bound(runner.withPropertyValues(
                PREFIX + "exchange.token-uri=http://token-exchange.svc.test/token", PREFIX + "exchange.client-id=edge",
                PREFIX + "exchange.client-secret=s3cr3t")).authority().exchange();

        assertThat(exchange.tokenUri()).isEqualTo("http://token-exchange.svc.test/token");
        assertThat(exchange.clientId()).isEqualTo("edge");
        assertThat(exchange.clientSecret()).isEqualTo("s3cr3t");

        BoundrailContextProperties relative = bound(runner.withPropertyValues(PREFIX + "propagation=forward",
                PREFIX + "exchange.token-uri=relative"));
        assertThat(relative.authority().exchange().tokenUri()).isEqualTo("relative");
    }

    @Test
    @DisplayName("ID-06 toString of the exchange properties and of BoundrailContextProperties does not contain the client secret")
    void toStringDoesNotContainTheClientSecret() {
        BoundrailContextProperties properties = bound(runner.withPropertyValues(PREFIX + "exchange.client-id=edge",
                PREFIX + "exchange.client-secret=s3cr3t"));
        BoundrailContextProperties unset = bound(runner);

        assertThat(properties.authority().exchange().toString()).contains("clientId=edge", "clientSecret=<redacted>")
                .doesNotContain("s3cr3t");
        assertThat(properties.toString()).contains("clientSecret=<redacted>").doesNotContain("s3cr3t");
        assertThat(unset.authority().exchange().toString()).contains("clientSecret=null");
    }
}
