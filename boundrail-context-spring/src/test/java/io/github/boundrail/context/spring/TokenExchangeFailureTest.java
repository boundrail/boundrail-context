package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Destination.Kind.INTERNAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.boundrail.context.Authority;
import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.Destination;
import io.github.boundrail.context.DestinationClassifier;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.spring.support.RecordingServer;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/** The built-in {@code exchange} propagator's failures: {@code TokenExchangeException}, and the call is not sent (ID-12, ID-11, ID-06). */
@ExtendWith(OutputCaptureExtension.class)
class TokenExchangeFailureTest {

    private static final String PREFIX = "boundrail.context.authority.";
    private static final String CLIENT_SECRET = "edge-secret-4711";
    private static final String CREDENTIAL = "alice-credential-0815";
    private static final Authority ALICE = Authority.of("alice", CREDENTIAL);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class, RestClientAutoConfiguration.class,
                    RestTemplateAutoConfiguration.class));

    /** {@return the runner with the built-in {@code exchange} propagator selected, against {@code tokenUri}} */
    private ApplicationContextRunner exchange(String tokenUri) {
        return runner.withPropertyValues(PREFIX + "propagation=exchange",
                PREFIX + "exchange.token-uri=" + tokenUri,
                PREFIX + "exchange.client-id=edge",
                PREFIX + "exchange.client-secret=" + CLIENT_SECRET);
    }

    private ApplicationContextRunner exchange(RecordingServer tokenEndpoint) {
        return exchange(tokenEndpoint.uri() + "/token");
    }

    static Stream<Arguments> invalidResponses() {
        return Stream.of(
                Arguments.of("status 201", 201, "{\"access_token\":\"t\"}"),
                Arguments.of("status 302", 302, ""),
                Arguments.of("status 400", 400, "{\"error\":\"invalid_request\"}"),
                Arguments.of("status 500", 500, "{\"access_token\":\"t\"}"),
                Arguments.of("empty body", 200, ""),
                Arguments.of("not json", 200, "not json"),
                Arguments.of("whole-body null", 200, "null"),
                Arguments.of("JSON array", 200, "[{\"access_token\":\"t\"}]"),
                Arguments.of("truncated", 200, "{\"access_token\":\"t"),
                Arguments.of("trailing garbage", 200, "{\"access_token\":\"t\"} trailing"),
                Arguments.of("access_token missing", 200, "{\"expires_in\":3600}"),
                Arguments.of("access_token null", 200, "{\"access_token\":null}"),
                Arguments.of("access_token empty", 200, "{\"access_token\":\"\"}"),
                Arguments.of("access_token 42", 200, "{\"access_token\":42}"),
                Arguments.of("access_token array", 200, "{\"access_token\":[\"t\"]}"),
                Arguments.of("expires_in -1", 200, "{\"access_token\":\"t\",\"expires_in\":-1}"),
                Arguments.of("expires_in -1e999999999", 200, "{\"access_token\":\"t\",\"expires_in\":-1e999999999}"),
                Arguments.of("expires_in 1.5", 200, "{\"access_token\":\"t\",\"expires_in\":1.5}"),
                Arguments.of("expires_in \"3600\"", 200, "{\"access_token\":\"t\",\"expires_in\":\"3600\"}"),
                Arguments.of("expires_in null", 200, "{\"access_token\":\"t\",\"expires_in\":null}"),
                Arguments.of("expires_in true", 200, "{\"access_token\":\"t\",\"expires_in\":true}"),
                Arguments.of("expires_in NaN", 200, "{\"access_token\":\"t\",\"expires_in\":NaN}"),
                Arguments.of("expires_in 1e2147483648", 200, "{\"access_token\":\"t\",\"expires_in\":1e2147483648}"),
                Arguments.of("duplicate access_token", 200, "{\"access_token\":\"a\",\"access_token\":\"b\"}"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("invalidResponses")
    @DisplayName("ID-12 a status other than 200, or a body that is not valid, throws TokenExchangeException and the request is not sent")
    void aStatusOtherThan200OrABodyThatIsNotValidThrowsTokenExchangeException(String name, int status, String body) {
        try (RecordingServer tokenEndpoint = RecordingServer.start(status, "application/json", body);
                RecordingServer orders = RecordingServer.start("ok")) {
            Throwable thrown = catchThrowable(() -> callOrders(exchange(tokenEndpoint), orders));

            assertThat(tokenEndpoint.requests()).hasSize(1);
            assertThat(thrown).isExactlyInstanceOf(TokenExchangeException.class);
            assertThat(thrown.getCause()).as("the parse exception is dropped").isNull();
            assertThat(orders.requests()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"3600.0", "1e3"})
    @DisplayName("ID-11 an expires_in of 3600.0 or 1e3 is valid")
    void anExpiresInOf3600Point0Or1e3IsValid(String expiresIn) {
        try (RecordingServer tokenEndpoint = RecordingServer.start(200, "application/json",
                "{\"access_token\":\"exchanged-orders\",\"expires_in\":" + expiresIn + "}");
                RecordingServer orders = RecordingServer.start("ok")) {
            callOrders(exchange(tokenEndpoint), orders);

            assertThat(orders.requests()).hasSize(1);
            assertThat(orders.requests().getFirst().headers().get("Authorization")).containsExactly("Bearer exchanged-orders");
        }
    }

    @Test
    @DisplayName("ID-12 an unreachable token endpoint throws TokenExchangeException with the I/O exception as its cause, and the request is "
            + "not sent")
    void anUnreachableTokenEndpointThrowsTokenExchangeExceptionWithTheIoExceptionAsItsCause() {
        try (RecordingServer orders = RecordingServer.start("ok")) {
            Throwable thrown = catchThrowable(() -> callOrders(exchange("http://127.0.0.1:" + refusedPort() + "/token"), orders));

            assertThat(thrown).isExactlyInstanceOf(TokenExchangeException.class);
            assertThat(thrown.getCause()).isInstanceOf(ConnectException.class);
            assertThat(orders.requests()).isEmpty();
        }
    }

    @Test
    @DisplayName("ID-12 an interrupted exchange throws TokenExchangeException and keeps the interrupt status")
    void anInterruptedExchangeThrowsTokenExchangeExceptionAndKeepsTheInterruptStatus() {
        boolean[] interruptedAfterwards = new boolean[1];
        try (RecordingServer tokenEndpoint = RecordingServer.start(200, "application/json", "{\"access_token\":\"t\"}");
                RecordingServer orders = RecordingServer.start("ok")) {
            Throwable thrown = catchThrowable(() -> callOrders(exchange(tokenEndpoint), orders, internal -> {
                Thread.currentThread().interrupt();
                try {
                    Context.with(BuiltInKeys.AUTHORITY, ALICE).run(() -> internal.get().uri("/").retrieve().toBodilessEntity());
                } finally {
                    // Read, and cleared, before the context closes.
                    interruptedAfterwards[0] = Thread.interrupted();
                }
            }));

            assertThat(thrown).isExactlyInstanceOf(TokenExchangeException.class);
            assertThat(thrown.getCause()).isInstanceOf(InterruptedException.class);
            assertThat(interruptedAfterwards[0]).isTrue();
            assertThat(orders.requests()).isEmpty();
        }
    }

    @Test
    @DisplayName("ID-12 no violation is reported for a failed exchange")
    void noViolationIsReportedForAFailedExchange() {
        List<Violation> violations = new CopyOnWriteArrayList<>();
        try (RecordingServer tokenEndpoint = RecordingServer.start(500, "application/json", "{}");
                RecordingServer orders = RecordingServer.start("ok")) {
            ApplicationContextRunner recording = exchange(tokenEndpoint).withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                    .withBean(ViolationReporter.Counter.class, () -> violations::add);

            Throwable thrown = catchThrowable(() -> callOrders(recording, orders));

            assertThat(thrown).isExactlyInstanceOf(TokenExchangeException.class);
            assertThat(violations).isEmpty();
            assertThat(orders.requests()).isEmpty();
        }
    }

    static Stream<Arguments> leakingResponses() {
        return Stream.of(
                Arguments.of(500, "{\"access_token\":\"leaked-token-9a7b\"}", "leaked-token-9a7b"),
                Arguments.of(200, "{\"access_token\":SECRETxyz}", "SECRETxyz"));
    }

    @ParameterizedTest(name = "[{index}] status {0}")
    @MethodSource("leakingResponses")
    @DisplayName("ID-12 the message names the audience and the status, and contains no credential, exchanged token, client secret or body")
    void theMessageNamesTheAudienceAndTheStatusAndContainsNoSecret(int status, String body, String token, CapturedOutput output) {
        try (RecordingServer tokenEndpoint = RecordingServer.start(status, "application/json", body);
                RecordingServer orders = RecordingServer.start("ok")) {
            Throwable thrown = catchThrowable(() -> callOrders(exchange(tokenEndpoint), orders));

            assertThat(thrown).isExactlyInstanceOf(TokenExchangeException.class);
            assertThat(thrown.getMessage()).contains("orders").contains(String.valueOf(status));
            // ID-06: the exception's message, every message in its cause chain, and the captured output.
            List<String> texts = new ArrayList<>();
            for (Throwable t = thrown; t != null; t = t.getCause()) {
                texts.add(String.valueOf(t.getMessage()));
            }
            texts.add(output.getAll());
            assertThat(texts).allSatisfy(text -> assertThat(text)
                    .doesNotContain(CREDENTIAL).doesNotContain(token).doesNotContain(CLIENT_SECRET).doesNotContain(body));
            assertThat(orders.requests()).isEmpty();
        }
    }

    @Test
    @DisplayName("ID-12 TokenExchangeException has no public constructor and reaches RestClient and RestTemplate callers unwrapped")
    void tokenExchangeExceptionHasNoPublicConstructorAndReachesCallersUnwrapped() {
        assertThat(Arrays.stream(TokenExchangeException.class.getDeclaredConstructors()).map(c -> c.getModifiers()))
                .noneMatch(Modifier::isPublic);
        DestinationClassifier internalByDefault = request -> request.declared().orElse(new Destination(INTERNAL, Optional.of("orders")));
        try (RecordingServer tokenEndpoint = RecordingServer.start(500, "application/json", "{}");
                RecordingServer orders = RecordingServer.start("ok")) {
            Throwable fromRestClient = catchThrowable(() -> callOrders(exchange(tokenEndpoint), orders));

            Throwable[] fromRestTemplate = new Throwable[1];
            exchange(tokenEndpoint).withBean(DestinationClassifier.class, () -> internalByDefault).run(context -> {
                RestTemplate template = context.getBean(RestTemplateBuilder.class).build();
                fromRestTemplate[0] = catchThrowable(() -> Context.with(BuiltInKeys.AUTHORITY, ALICE)
                        .run(() -> template.getForObject(orders.uri(), String.class)));
            });

            assertThat(fromRestClient).isExactlyInstanceOf(TokenExchangeException.class);
            assertThat(fromRestTemplate[0]).isExactlyInstanceOf(TokenExchangeException.class);
            assertThat(orders.requests()).isEmpty();
        }
    }

    /** {@return a loopback port nothing listens on} */
    private static int refusedPort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void callOrders(ApplicationContextRunner configured, RecordingServer orders) {
        callOrders(configured, orders,
                internal -> Context.with(BuiltInKeys.AUTHORITY, ALICE).run(() -> internal.get().uri("/").retrieve().toBodilessEntity()));
    }

    /**
     * Runs {@code call} with an internal {@code RestClient} to {@code orders}, rethrowing what it throws. The runner itself would wrap it
     * in an {@code IllegalStateException}.
     */
    private static void callOrders(ApplicationContextRunner configured, RecordingServer orders, Consumer<RestClient> call) {
        RuntimeException[] thrown = new RuntimeException[1];
        configured.run(context -> {
            RestClient internal = context.getBean(RestClient.Builder.class).baseUrl(orders.uri())
                    .apply(BoundrailClients.internal("orders")).build();
            try {
                call.accept(internal);
            } catch (RuntimeException e) {
                thrown[0] = e;
            }
        });
        if (thrown[0] != null) {
            throw thrown[0];
        }
    }
}
