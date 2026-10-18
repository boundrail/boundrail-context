package io.github.boundrail.context.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.AuthorityPropagator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

/** Startup validation of the built-in {@code exchange} propagator's settings (ID-04, ID-06, ID-11). */
class TokenExchangeConfigurationTest {

    private static final String PREFIX = "boundrail.context.authority.";
    private static final String TOKEN_URI = PREFIX + "exchange.token-uri";
    private static final String CLIENT_ID = PREFIX + "exchange.client-id";
    private static final String CLIENT_SECRET = PREFIX + "exchange.client-secret";
    private static final String SECRET = "s3cr3t-value";

    /** An application's own propagator, which replaces the built-in selection. */
    private static final AuthorityPropagator OWN = (authority, destination) -> Optional.empty();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class));

    /** Valid exchange settings with the built-in {@code exchange} propagator selected; a {@code null} value removes the property. */
    private static Map<String, Object> exchange(String... overrides) {
        Map<String, Object> properties = new HashMap<>(Map.of(PREFIX + "propagation", "exchange",
                TOKEN_URI, "http://token-exchange.svc.test/token", CLIENT_ID, "edge", CLIENT_SECRET, SECRET));
        for (int i = 0; i < overrides.length; i += 2) {
            if (overrides[i + 1] == null) {
                properties.remove(overrides[i]);
            } else {
                properties.put(overrides[i], overrides[i + 1]);
            }
        }
        return properties;
    }

    /** Adds the properties as the first property source, so values keep their spaces and may be empty. */
    private static ApplicationContextRunner withProperties(ApplicationContextRunner runner, Map<String, Object> properties) {
        return runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("test", properties)));
    }

    /** Lazy initialization as {@code SpringApplication} sets it up for {@code spring.main.lazy-initialization=true}. */
    private static ApplicationContextRunner lazy(ApplicationContextRunner runner, boolean lazy) {
        return lazy ? runner.withPropertyValues("spring.main.lazy-initialization=true")
                .withInitializer(context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
                : runner;
    }

    /** {@return the messages of the throwable and of each of its causes} */
    private static List<String> messageChain(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.add(String.valueOf(cause.getMessage()));
        }
        return messages;
    }

    /** Asserts that startup failed with an {@code IllegalArgumentException} without a cause, naming {@code subject}. */
    private static void assertFailsNaming(ApplicationContextRunner runner, String subject, String... absent) {
        runner.run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            assertThat(failure).rootCause().isInstanceOf(IllegalArgumentException.class).hasMessageContaining(subject);
            for (String text : absent) {
                assertThat(messageChain(failure)).noneMatch(message -> message.contains(text));
            }
        });
    }

    static Stream<Arguments> tokenUris() {
        List<Arguments> arguments = new ArrayList<>();
        for (String tokenUri : new String[] {null, "", "   ", "/token", "ftp://h/token", "http:foo", "http:///x", "not a uri",
                "https://edge:pw@host/token", "HTTPS://h/token"}) {
            for (boolean lazy : new boolean[] {false, true}) {
                arguments.add(Arguments.of(tokenUri == null ? "unset" : "'" + tokenUri + "'", Optional.ofNullable(tokenUri), lazy));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "token-uri {0}, lazy initialization: {2}")
    @MethodSource("tokenUris")
    @DisplayName("ID-11 with the built-in exchange propagator a missing, blank, relative, non-HTTP or host-less token-uri, or one with "
            + "userinfo, fails startup, also with lazy initialization")
    void anInvalidTokenUriFailsStartup(String name, Optional<String> tokenUri, boolean lazy) {
        ApplicationContextRunner configured = lazy(withProperties(runner, exchange(TOKEN_URI, tokenUri.orElse(null))), lazy);
        if (tokenUri.equals(Optional.of("HTTPS://h/token"))) {
            configured.run(context -> assertThat(context).hasNotFailed());
        } else {
            assertFailsNaming(configured, TOKEN_URI, "edge:pw");
        }
    }

    static Stream<Arguments> clientCredentials() {
        List<Arguments> arguments = new ArrayList<>();
        for (String property : new String[] {CLIENT_ID, CLIENT_SECRET}) {
            for (String value : new String[] {null, "", "   "}) {
                for (boolean lazy : new boolean[] {false, true}) {
                    arguments.add(Arguments.of(property, value == null ? "unset" : "'" + value + "'", Optional.ofNullable(value), lazy));
                }
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0} {1}, lazy initialization: {3}")
    @MethodSource("clientCredentials")
    @DisplayName("ID-11 a missing or blank client-id or client-secret fails startup, also with lazy initialization")
    void aMissingOrBlankClientCredentialFailsStartup(String property, String name, Optional<String> value, boolean lazy) {
        assertFailsNaming(lazy(withProperties(runner, exchange(property, value.orElse(null))), lazy), property);
    }

    static Stream<Arguments> failuresWithASecret() {
        return Stream.of(
                Arguments.of(TOKEN_URI, exchange(TOKEN_URI, "/token")),
                Arguments.of(TOKEN_URI, exchange(TOKEN_URI, "https://edge:" + SECRET + "@host/token")),
                Arguments.of(TOKEN_URI, exchange(TOKEN_URI, "not a uri " + SECRET)),
                Arguments.of(CLIENT_ID, exchange(CLIENT_ID, "   ")));
    }

    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("failuresWithASecret")
    @DisplayName("ID-06 the startup failure names the property and never contains the client secret")
    void theStartupFailureNamesThePropertyAndNeverContainsTheClientSecret(String property, Map<String, Object> properties) {
        assertFailsNaming(withProperties(runner, properties), property, SECRET);
    }

    @Test
    @DisplayName("ID-04 with an AuthorityPropagator bean, or with forward, the exchange properties are not validated")
    void exchangePropertiesAreNotValidatedWhenReplacedOrForward() {
        Map<String, Object> invalid = exchange(TOKEN_URI, "/relative", CLIENT_ID, null);

        withProperties(runner, invalid).withBean(AuthorityPropagator.class, () -> OWN).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AuthorityPropagator.class)).isSameAs(OWN);
        });
        Map<String, Object> forward = new HashMap<>(invalid);
        forward.put(PREFIX + "propagation", "forward");
        withProperties(runner, forward).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("ID-11 with the built-in exchange propagator and no Jackson 3, startup fails naming Jackson 3, also with lazy initialization; "
            + "forward or an AuthorityPropagator bean start without it")
    void withoutJackson3TheBuiltInExchangePropagatorFailsStartup() {
        ApplicationContextRunner withoutJackson = runner.withClassLoader(new FilteredClassLoader("tools.jackson"));

        for (boolean lazy : new boolean[] {false, true}) {
            assertFailsNaming(lazy(withProperties(withoutJackson, exchange()), lazy), "tools.jackson.databind", SECRET);
        }
        withProperties(withoutJackson, exchange(PREFIX + "propagation", "forward"))
                .run(context -> assertThat(context).hasNotFailed());
        withProperties(withoutJackson, exchange()).withBean(AuthorityPropagator.class, () -> OWN)
                .run(context -> assertThat(context).hasNotFailed());
    }
}
