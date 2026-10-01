package io.github.boundrail.context.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.spring.support.RecordingServer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.restclient.RestTemplateCustomizer;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

class ClientRegistrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class,
                    RestClientAutoConfiguration.class, RestTemplateAutoConfiguration.class));

    @Test
    @DisplayName("OUT-01 the interceptor is added to the auto-configured RestClient.Builder")
    void theInterceptorIsAddedToTheAutoConfiguredRestClientBuilder() {
        runner.run(context -> {
            List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>();
            context.getBean(RestClient.Builder.class).requestInterceptors(interceptors::addAll);

            assertThat(interceptors).hasAtLeastOneElementOfType(ContextClientInterceptor.class);
        });
    }

    @Test
    @DisplayName("OUT-01 the interceptor is added to the auto-configured RestTemplateBuilder")
    void theInterceptorIsAddedToTheAutoConfiguredRestTemplateBuilder() {
        runner.run(context -> assertThat(context.getBean(RestTemplateBuilder.class).build().getInterceptors())
                .hasAtLeastOneElementOfType(ContextClientInterceptor.class));
    }

    @Test
    @DisplayName("OUT-01 the interceptor executes the request exactly once and returns its response")
    void theInterceptorExecutesTheRequestExactlyOnceAndReturnsItsResponse() {
        try (RecordingServer server = RecordingServer.start("pong")) {
            runner.run(context -> {
                String body = context.getBean(RestClient.Builder.class).baseUrl(server.uri()).build()
                        .get().uri("/ping").retrieve().body(String.class);

                assertThat(body).isEqualTo("pong");
                assertThat(server.requests()).singleElement()
                        .satisfies(request -> assertThat(request.uri().getPath()).isEqualTo("/ping"));
            });
        }
    }

    @Test
    @DisplayName("OUT-01 without spring-boot-restclient no client customizer is registered")
    void withoutSpringBootRestclientNoClientCustomizerIsRegistered() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader("org.springframework.boot.restclient"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RestClientCustomizer.class);
                    assertThat(context).doesNotHaveBean(RestTemplateCustomizer.class);
                });
    }
}
