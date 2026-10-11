package io.github.boundrail.context.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.Deadline;
import io.github.boundrail.context.DeadlineExceededException;
import io.github.boundrail.context.spring.support.RecordingServer;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** The startup WARN when Boot's auto-configured request factory is not the JDK {@code HttpClient} one (DL-07). */
@ExtendWith(OutputCaptureExtension.class)
class RequestFactoryCheckTest {

    private static final String PROPERTY = "spring.http.clients.imperative.factory";

    /** The startup WARN entries about timeout capping. */
    private static List<String> warnings(CapturedOutput output) {
        return output.getOut().lines().filter(line -> line.contains("WARN") && line.contains(PROPERTY)).toList();
    }

    static Stream<Arguments> factories() {
        ApplicationContextRunner withHttpClients = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class, HttpClientAutoConfiguration.class,
                        ImperativeHttpClientAutoConfiguration.class, RestClientAutoConfiguration.class));
        ApplicationContextRunner withoutHttpClients = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class, RestClientAutoConfiguration.class));
        return Stream.of(
                Arguments.of(Named.of("DL-07 one WARN is logged at startup when Boot's auto-configured request factory is not the JDK HttpClient"
                        + " one, and DL-05 and DL-06 still apply", withHttpClients.withPropertyValues(PROPERTY + "=simple")), 1),
                Arguments.of(Named.of("DL-07 no WARN is logged with the JDK HttpClient factory",
                        withHttpClients.withPropertyValues(PROPERTY + "=jdk")), 0),
                Arguments.of(Named.of("DL-07 no WARN is logged without Boot's HTTP client auto-configuration", withoutHttpClients), 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    @DisplayName("DL-07 the startup WARN reads Boot's auto-configured request factory")
    void theStartupWarnReadsBootsAutoConfiguredRequestFactory(ApplicationContextRunner runner, int expectedWarnings, CapturedOutput output) {
        try (RecordingServer server = RecordingServer.start("ok")) {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                RestClient client = context.getBean(RestClient.Builder.class).baseUrl(server.uri()).apply(BoundrailClients.internal("orders"))
                        .build();

                Context.with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ofSeconds(10)))
                        .run(() -> client.get().uri("/").retrieve().toBodilessEntity());
                Throwable expired = catchThrowable(() -> Context.with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ZERO))
                        .run(() -> client.get().uri("/").retrieve().toBodilessEntity()));

                assertThat(server.requests()).as("DL-05: only the live call is sent").singleElement()
                        .satisfies(request -> assertThat(request.headers().keySet()).anyMatch("Boundrail-Deadline-Ms"::equalsIgnoreCase));
                assertThat(expired).as("DL-06").isInstanceOf(DeadlineExceededException.class);
                assertThat(((DeadlineExceededException) expired).stage()).isEqualTo(DeadlineExceededException.Stage.BEFORE_SEND);
            });
        }

        assertThat(warnings(output)).hasSize(expectedWarnings);
        warnings(output).forEach(warning -> assertThat(warning).contains("timeout capping is not guaranteed"));
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoBuilders {

        @Bean
        ClientHttpRequestFactoryBuilder<?> firstBuilder() {
            return ClientHttpRequestFactoryBuilder.simple();
        }

        @Bean
        ClientHttpRequestFactoryBuilder<?> secondBuilder() {
            return ClientHttpRequestFactoryBuilder.simple();
        }
    }

    @Test
    @DisplayName("DL-07 several request factory builders with none primary give no WARN and do not fail startup")
    void severalRequestFactoryBuildersWithNonePrimaryGiveNoWarnAndDoNotFailStartup(CapturedOutput output) {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
                .withUserConfiguration(TwoBuilders.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RequestFactoryCheck.class);
                });

        assertThat(warnings(output)).isEmpty();
    }
}
