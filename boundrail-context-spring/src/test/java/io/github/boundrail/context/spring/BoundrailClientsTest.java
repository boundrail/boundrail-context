package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Destination.Kind.EXTERNAL;
import static io.github.boundrail.context.Destination.Kind.INTERNAL;
import static io.github.boundrail.context.Destination.Kind.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.boundrail.context.Destination;
import io.github.boundrail.context.DestinationClassifier;
import io.github.boundrail.context.HostPatternClassifier;
import io.github.boundrail.context.OutboundRequest;
import io.github.boundrail.context.spring.support.RecordingServer;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class BoundrailClientsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class,
                    RestClientAutoConfiguration.class, RestTemplateAutoConfiguration.class));

    private final ApplicationContextRunner recording = runner.withBean(RecordingClassifier.class, RecordingClassifier::new);

    @Test
    @DisplayName("OUT-03 internal(service) declares Destination(INTERNAL, service) for every request of the client")
    void internalDeclaresAnInternalDestinationForEveryRequestOfTheClient() {
        try (RecordingServer server = RecordingServer.start("ok")) {
            recording.run(context -> {
                RestClient client = context.getBean(RestClient.Builder.class).baseUrl(server.uri())
                        .apply(BoundrailClients.internal("orders")).build();

                client.get().uri("/first").retrieve().toBodilessEntity();
                client.get().uri("/second").retrieve().toBodilessEntity();

                Optional<Destination> orders = Optional.of(new Destination(INTERNAL, Optional.of("orders")));
                assertThat(context.getBean(RecordingClassifier.class).requests)
                        .extracting(OutboundRequest::declared)
                        .containsExactly(orders, orders);
            });
        }
    }

    @Test
    @DisplayName("OUT-03 external() declares Destination(EXTERNAL, empty)")
    void externalDeclaresAnExternalDestination() {
        try (RecordingServer server = RecordingServer.start("ok")) {
            recording.run(context -> {
                RestClient client = context.getBean(RestClient.Builder.class).baseUrl(server.uri())
                        .apply(BoundrailClients.external()).build();

                client.get().uri("/first").retrieve().toBodilessEntity();
                client.get().uri("/second").retrieve().toBodilessEntity();

                Optional<Destination> external = Optional.of(new Destination(EXTERNAL, Optional.empty()));
                assertThat(context.getBean(RecordingClassifier.class).requests)
                        .extracting(OutboundRequest::declared)
                        .containsExactly(external, external);
            });
        }
    }

    @Test
    @DisplayName("OUT-02 the interceptor classifies the request URI and the declaration")
    void theInterceptorClassifiesTheRequestUriAndTheDeclaration() {
        try (RecordingServer server = RecordingServer.start("ok")) {
            recording.run(context -> {
                URI restClientUri = server.uri().resolve("/rest-client?q=1");
                URI restTemplateUri = server.uri().resolve("/rest-template?q=2");

                context.getBean(RestClient.Builder.class).build().get().uri(restClientUri).retrieve().toBodilessEntity();
                context.getBean(RestTemplateBuilder.class).build().getForObject(restTemplateUri, String.class);

                assertThat(context.getBean(RecordingClassifier.class).requests).containsExactly(
                        new OutboundRequest(restClientUri, Optional.empty()),
                        new OutboundRequest(restTemplateUri, Optional.empty()));
            });
        }
    }

    @Test
    @DisplayName("OUT-03 the default classifier is a HostPatternClassifier from destinations.internal-hosts and external-hosts")
    void theDefaultClassifierIsAHostPatternClassifierFromTheDestinationsProperties() {
        OutboundRequest internalHost = undeclared("http://orders.svc.test/orders");
        OutboundRequest otherInternalHost = undeclared("http://billing.internal.test/invoices");
        OutboundRequest externalHost = undeclared("https://api.partner.test/v1");
        OutboundRequest otherHost = undeclared("https://example.test/");

        runner.withPropertyValues(
                        "boundrail.context.destinations.internal-hosts=orders.svc.test,*.internal.test",
                        "boundrail.context.destinations.external-hosts=api.partner.test")
                .run(context -> {
                    DestinationClassifier classifier = context.getBean(DestinationClassifier.class);

                    assertThat(classifier).isInstanceOf(HostPatternClassifier.class);
                    assertThat(classifier.classify(internalHost)).isEqualTo(new Destination(INTERNAL, Optional.empty()));
                    assertThat(classifier.classify(otherInternalHost)).isEqualTo(new Destination(INTERNAL, Optional.empty()));
                    assertThat(classifier.classify(externalHost)).isEqualTo(new Destination(EXTERNAL, Optional.empty()));
                    assertThat(classifier.classify(otherHost)).isEqualTo(new Destination(UNKNOWN, Optional.empty()));
                });
        runner.run(context -> {
            BoundrailContextProperties properties = context.getBean(BoundrailContextProperties.class);
            DestinationClassifier classifier = context.getBean(DestinationClassifier.class);

            assertThat(properties.destinations().internalHosts()).isEmpty();
            assertThat(properties.destinations().externalHosts()).isEmpty();
            assertThat(classifier.classify(internalHost)).isEqualTo(new Destination(UNKNOWN, Optional.empty()));
            assertThat(classifier.classify(externalHost)).isEqualTo(new Destination(UNKNOWN, Optional.empty()));
        });
    }

    @Test
    @DisplayName("OUT-03 a DestinationClassifier bean replaces the default")
    void aDestinationClassifierBeanReplacesTheDefault() {
        try (RecordingServer server = RecordingServer.start("ok")) {
            recording.run(context -> {
                assertThat(context).hasSingleBean(DestinationClassifier.class);
                assertThat(context).doesNotHaveBean(HostPatternClassifier.class);

                context.getBean(RestClient.Builder.class).build().get().uri(server.uri()).retrieve().toBodilessEntity();

                assertThat(context.getBean(RecordingClassifier.class).requests).hasSize(1);
            });
        }
    }

    @Test
    @DisplayName("DEST-04 an invalid destinations pattern fails startup")
    void anInvalidDestinationsPatternFailsStartup() {
        runner.withPropertyValues("boundrail.context.destinations.internal-hosts=*")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    @DisplayName("DEST-04 an invalid destinations pattern fails startup with lazy initialization")
    void anInvalidDestinationsPatternFailsStartupWithLazyInitialization() {
        runner.withPropertyValues("spring.main.lazy-initialization=true", "boundrail.context.destinations.internal-hosts=*")
                .withInitializer(context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    @DisplayName("CORE-13 BoundrailClients.internal(null) throws NullPointerException")
    void internalNullThrowsNullPointerException() {
        assertThatNullPointerException().isThrownBy(() -> BoundrailClients.internal(null));
    }

    private static OutboundRequest undeclared(String uri) {
        return new OutboundRequest(URI.create(uri), Optional.empty());
    }

    /** Records every request it classifies and answers {@code UNKNOWN}. */
    static final class RecordingClassifier implements DestinationClassifier {

        final List<OutboundRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public Destination classify(OutboundRequest request) {
            requests.add(request);
            return new Destination(UNKNOWN, Optional.empty());
        }
    }
}
