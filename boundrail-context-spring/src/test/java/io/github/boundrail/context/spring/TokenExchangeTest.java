package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Boundary.INTERNAL_CALL;
import static io.github.boundrail.context.Destination.Kind.INTERNAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.boundrail.context.Authority;
import io.github.boundrail.context.AuthorityPropagator;
import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.Deadline;
import io.github.boundrail.context.Destination;
import io.github.boundrail.context.DestinationClassifier;
import io.github.boundrail.context.Initiator;
import io.github.boundrail.context.spring.support.RecordingServer;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

/** The built-in {@code exchange} propagator's RFC 8693 request and its handling of a valid response (ID-04, ID-11, CORE-16, RT-09). */
class TokenExchangeTest {

    private static final String PREFIX = "boundrail.context.authority.";
    private static final Authority ALICE = Authority.of("alice", "alice-token");
    private static final String TOKEN_RESPONSE = "{\"access_token\":\"exchanged-orders\"}";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class, RestClientAutoConfiguration.class,
                    RestTemplateAutoConfiguration.class));

    /** {@return the runner with the built-in {@code exchange} propagator selected, against {@code tokenEndpoint}'s {@code /token}} */
    private ApplicationContextRunner exchange(RecordingServer tokenEndpoint, String clientId, String clientSecret) {
        return runner.withPropertyValues(PREFIX + "propagation=exchange",
                PREFIX + "exchange.token-uri=" + tokenEndpoint.uri() + "/token",
                PREFIX + "exchange.client-id=" + clientId,
                PREFIX + "exchange.client-secret=" + clientSecret);
    }

    private ApplicationContextRunner exchange(RecordingServer tokenEndpoint) {
        return exchange(tokenEndpoint, "edge", "edge-secret");
    }

    @Test
    @DisplayName("ID-11 an internal call sends one form POST to token-uri with the RFC 8693 fields and audience, and carries the returned "
            + "token")
    void anInternalCallSendsOneFormPostWithTheRfc8693FieldsAndCarriesTheReturnedToken() {
        try (RecordingServer tokenEndpoint = RecordingServer.start(200, "application/json", TOKEN_RESPONSE);
                RecordingServer orders = RecordingServer.start("ok")) {
            callOrders(exchange(tokenEndpoint), orders);

            assertThat(tokenEndpoint.requests()).hasSize(1);
            RecordingServer.Request exchangeRequest = tokenEndpoint.requests().getFirst();
            assertThat(exchangeRequest.method()).isEqualTo("POST");
            assertThat(exchangeRequest.uri().getPath()).isEqualTo("/token");
            assertThat(header(exchangeRequest, "Content-Type")).containsExactly("application/x-www-form-urlencoded");
            assertThat(header(exchangeRequest, "Accept")).containsExactly("application/json");
            assertThat(form(exchangeRequest.body())).containsExactlyInAnyOrder(
                    "grant_type=urn:ietf:params:oauth:grant-type:token-exchange",
                    "subject_token=alice-token",
                    "subject_token_type=urn:ietf:params:oauth:token-type:access_token",
                    "audience=orders");
            assertThat(orders.requests()).hasSize(1);
            assertThat(header(orders.requests().getFirst(), "Authorization")).containsExactly("Bearer exchanged-orders");
        }
    }

    @Test
    @DisplayName("ID-11 the client authenticates with HTTP Basic, each part form-encoded as RFC 6749 section 2.3.1 defines")
    void theClientAuthenticatesWithHttpBasicEachPartFormEncoded() {
        try (RecordingServer tokenEndpoint = RecordingServer.start(200, "application/json", TOKEN_RESPONSE);
                RecordingServer orders = RecordingServer.start("ok")) {
            callOrders(exchange(tokenEndpoint, "a b:c", "p&ß"), orders);

            String expected = Base64.getEncoder().encodeToString("a+b%3Ac:p%26%C3%9F".getBytes(StandardCharsets.US_ASCII));
            assertThat(tokenEndpoint.requests()).hasSize(1);
            assertThat(header(tokenEndpoint.requests().getFirst(), "Authorization")).containsExactly("Basic " + expected);
        }
    }

    static Stream<Arguments> responsesWithoutTokenTypeChecks() {
        return Stream.of(
                Arguments.of("token_type absent", "application/json", TOKEN_RESPONSE),
                Arguments.of("token_type mac", "application/json", "{\"access_token\":\"exchanged-orders\",\"token_type\":\"mac\"}"),
                Arguments.of("no Content-Type", null, TOKEN_RESPONSE),
                Arguments.of("Content-Type text/plain", "text/plain", TOKEN_RESPONSE));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("responsesWithoutTokenTypeChecks")
    @DisplayName("ID-11 token_type and Content-Type are not checked")
    void tokenTypeAndContentTypeAreNotChecked(String name, String contentType, String body) {
        try (RecordingServer tokenEndpoint = RecordingServer.start(200, contentType, body);
                RecordingServer orders = RecordingServer.start("ok")) {
            callOrders(exchange(tokenEndpoint), orders);

            assertThat(tokenEndpoint.requests()).hasSize(1);
            assertThat(header(orders.requests().getFirst(), "Authorization")).containsExactly("Bearer exchanged-orders");
        }
    }

    @Test
    @DisplayName("ID-11 the exchange request carries no context headers and does not pass through the library's interceptor")
    void theExchangeRequestCarriesNoContextHeadersAndDoesNotPassThroughTheInterceptor() {
        ContextKey<String> tenant = ContextKey.of("tenant", String.class);
        ContextPolicy tenantPolicy = ContextPolicy.builder()
                .key(tenant, rules -> rules.codec(Codecs.string()).wireName("Tenant-Id").propagateTo(INTERNAL_CALL))
                .build();
        // Every request without a declaration, as the token endpoint's would be through a Spring client, is internal.
        DestinationClassifier internalByDefault = request -> request.declared().orElse(new Destination(INTERNAL, Optional.of("orders")));
        try (RecordingServer tokenEndpoint = RecordingServer.start(200, "application/json", TOKEN_RESPONSE);
                RecordingServer orders = RecordingServer.start("ok")) {
            exchange(tokenEndpoint).withBean(ContextPolicy.class, () -> tenantPolicy)
                    .withBean(DestinationClassifier.class, () -> internalByDefault)
                    .run(context -> {
                        RestClient internal = context.getBean(RestClient.Builder.class).baseUrl(orders.uri())
                                .apply(BoundrailClients.internal("orders")).build();

                        Context.with(BuiltInKeys.AUTHORITY, ALICE).with(tenant, "acme")
                                .with(BuiltInKeys.INITIATOR, Initiator.of("user:alice"))
                                .with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ofMinutes(1)))
                                .run(() -> internal.get().uri("/").retrieve().toBodilessEntity());
                    });

            RecordingServer.Request call = orders.requests().getFirst();
            assertThat(header(call, "Tenant-Id")).as("the call itself carries the context").containsExactly("acme");
            assertThat(header(call, "Boundrail-Initiator")).as("the call itself carries the context").containsExactly("user:alice");
            assertThat(header(call, "Boundrail-Deadline-Ms")).as("the call itself carries the context").hasSize(1);
            assertThat(tokenEndpoint.requests()).hasSize(1);
            RecordingServer.Request exchangeRequest = tokenEndpoint.requests().getFirst();
            assertThat(header(exchangeRequest, "Tenant-Id")).isEmpty();
            assertThat(header(exchangeRequest, "Boundrail-Initiator")).isEmpty();
            assertThat(header(exchangeRequest, "Boundrail-Deadline-Ms")).isEmpty();
            assertThat(header(exchangeRequest, "Authorization")).noneMatch(value -> value.startsWith("Bearer"));
        }
    }

    @Test
    @DisplayName("ID-11 a body that is JSON null is not a token: the call is not sent")
    void aBodyThatIsJsonNullIsNotAToken() {
        try (RecordingServer tokenEndpoint = RecordingServer.start(200, "application/json", "null");
                RecordingServer orders = RecordingServer.start("ok")) {
            Throwable thrown = catchThrowable(() -> callOrders(exchange(tokenEndpoint), orders));

            assertThat(tokenEndpoint.requests()).hasSize(1);
            assertThat(thrown).isNotNull();
            assertThat(orders.requests()).isEmpty();
        }
    }

    @Test
    @DisplayName("CORE-16 the exchange propagator rejects a null authority or destination with NullPointerException")
    void theExchangePropagatorRejectsANullAuthorityOrDestination() {
        try (RecordingServer tokenEndpoint = RecordingServer.start(200, "application/json", TOKEN_RESPONSE)) {
            exchange(tokenEndpoint).run(context -> {
                AuthorityPropagator propagator = context.getBean(AuthorityPropagator.class);
                assertThat(propagator).as("the exchange propagator is selected").isNotInstanceOf(ForwardAuthorityPropagator.class);
                Destination orders = new Destination(INTERNAL, Optional.of("orders"));

                assertThatNullPointerException().isThrownBy(() -> propagator.outboundCredential(null, orders));
                assertThatNullPointerException().isThrownBy(() -> propagator.outboundCredential(ALICE, null));
            });
            assertThat(tokenEndpoint.requests()).isEmpty();
        }
    }

    @Test
    @DisplayName("RT-09 the exchange propagator's HTTP client is closed when the context closes")
    void theExchangePropagatorsHttpClientIsClosedWhenTheContextCloses() {
        HttpClient[] client = new HttpClient[1];
        try (RecordingServer tokenEndpoint = RecordingServer.start(200, "application/json", TOKEN_RESPONSE)) {
            exchange(tokenEndpoint).run(context -> {
                client[0] = ((ExchangeAuthorityPropagator) context.getBean(AuthorityPropagator.class)).httpClient();
                assertThat(client[0].isTerminated()).as("open while the context runs").isFalse();
            });
        }
        assertThat(client[0].isTerminated()).as("after the context's close()").isTrue();
    }

    /**
     * Makes one internal call to {@code orders} with {@code ALICE} bound, rethrowing what the call throws. The runner itself would wrap
     * it in an {@code IllegalStateException}.
     */
    private static void callOrders(ApplicationContextRunner configured, RecordingServer orders) {
        RuntimeException[] thrown = new RuntimeException[1];
        configured.run(context -> {
            RestClient internal = context.getBean(RestClient.Builder.class).baseUrl(orders.uri())
                    .apply(BoundrailClients.internal("orders")).build();
            try {
                Context.with(BuiltInKeys.AUTHORITY, ALICE).run(() -> internal.get().uri("/").retrieve().toBodilessEntity());
            } catch (RuntimeException e) {
                thrown[0] = e;
            }
        });
        if (thrown[0] != null) {
            throw thrown[0];
        }
    }

    /** {@return the form's {@code name=value} pairs, each part decoded} */
    private static List<String> form(String body) {
        return Arrays.stream(body.split("&"))
                .map(pair -> pair.split("=", 2))
                .map(parts -> URLDecoder.decode(parts[0], StandardCharsets.UTF_8) + "="
                        + URLDecoder.decode(parts[1], StandardCharsets.UTF_8))
                .toList();
    }

    /** {@return every value the server received under the header name, compared ignoring case} */
    private static List<String> header(RecordingServer.Request request, String name) {
        return request.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .toList();
    }
}
