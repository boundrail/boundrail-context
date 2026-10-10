package io.github.boundrail.context.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextBinding;
import io.github.boundrail.context.Deadline;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.spring.support.RecordingServer;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/** The remaining time the library's interceptor writes to internal calls as {@code Boundrail-Deadline-Ms} (DL-05). */
class OutboundDeadlineTest {

    private static final String HEADER = "Boundrail-Deadline-Ms";
    private static final Consumer<HttpHeaders> NO_HEADERS = headers -> { };

    /** Micrometer is hidden, so the recording counter is the only {@code ViolationReporter.Counter} bean. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class, RestClientAutoConfiguration.class))
            .withClassLoader(new FilteredClassLoader(MeterRegistry.class));

    /** The request the server received and the violations reported while sending it. */
    private record Exchange(RecordingServer.Request request, List<Violation> violations) {
    }

    /** Sends one GET through a client from the auto-configured {@code RestClient.Builder}, inside the binding, if any. */
    private Exchange exchange(Consumer<RestClient.Builder> declaration, ContextBinding binding, Consumer<HttpHeaders> applicationHeaders,
            String... properties) {
        List<Violation> violations = new CopyOnWriteArrayList<>();
        try (RecordingServer server = RecordingServer.start("ok")) {
            runner.withPropertyValues(properties)
                    .withBean(ViolationReporter.Counter.class, () -> violations::add)
                    .run(context -> {
                        RestClient client = context.getBean(RestClient.Builder.class).baseUrl(server.uri()).apply(declaration).build();
                        Runnable call = () -> client.get().uri("/").headers(applicationHeaders).retrieve().toBodilessEntity();
                        if (binding == null) {
                            call.run();
                        } else {
                            binding.run(call);
                        }
                    });
            assertThat(server.requests()).hasSize(1);
            return new Exchange(server.requests().getFirst(), List.copyOf(violations));
        }
    }

    private static ContextBinding tenSeconds() {
        return Context.with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ofSeconds(10)));
    }

    /** {@return every value the server received under the header name, compared ignoring case} */
    private static List<String> header(RecordingServer.Request request, String name) {
        return request.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .toList();
    }

    /** Asserts that the request carries one value, the remaining milliseconds of a 10s deadline set just before the call. */
    private static void assertRemainingOfTenSeconds(RecordingServer.Request request) {
        assertThat(header(request, HEADER)).singleElement().satisfies(value -> {
            assertThat(value).matches("[0-9]+");
            assertThat(Long.parseLong(value)).isBetween(9000L, 10000L);
        });
    }

    @Test
    @DisplayName("DL-05 an internal call with a deadline bound carries Boundrail-Deadline-Ms with the remaining milliseconds, never more than the sender's")
    void anInternalCallCarriesTheRemainingMilliseconds() {
        Exchange internal = exchange(BoundrailClients.internal("orders"), tenSeconds(), NO_HEADERS);
        Exchange external = exchange(BoundrailClients.external(), tenSeconds(), NO_HEADERS);
        Exchange unknown = exchange(builder -> { }, tenSeconds(), NO_HEADERS);

        assertRemainingOfTenSeconds(internal.request());
        assertThat(header(external.request(), HEADER)).as("external").isEmpty();
        assertThat(header(unknown.request(), HEADER)).as("unknown").isEmpty();
    }

    @Test
    @DisplayName("DL-05 the remaining time is rounded down to a whole millisecond")
    void theRemainingTimeIsRoundedDownToAWholeMillisecond() {
        assertThat(ContextClientInterceptor.headerValue(Duration.ofNanos(1_999_999))).isEqualTo("1");
        assertThat(ContextClientInterceptor.headerValue(Duration.ofNanos(999_999))).isEqualTo("0");
        assertThat(ContextClientInterceptor.headerValue(Duration.ofNanos(1))).isEqualTo("0");
    }

    @Test
    @DisplayName("DL-05 an application's valid smaller value is kept, a larger one is replaced by the remaining time, and an invalid one is replaced")
    void anApplicationsValidSmallerValueIsKeptOthersAreReplaced() {
        Exchange smaller = exchange(BoundrailClients.internal("orders"), tenSeconds(), headers -> headers.set(HEADER, "100"));
        Exchange larger = exchange(BoundrailClients.internal("orders"), tenSeconds(), headers -> headers.set(HEADER, "60000"));
        Exchange invalid = exchange(BoundrailClients.internal("orders"), tenSeconds(), headers -> headers.set(HEADER, "abc"));
        Exchange two = exchange(BoundrailClients.internal("orders"), tenSeconds(), headers -> headers.addAll(HEADER, List.of("100", "200")));
        Exchange unbound = exchange(BoundrailClients.internal("orders"), null, headers -> headers.set(HEADER, "abc"));

        assertThat(header(smaller.request(), HEADER)).as("a valid smaller value").containsExactly("100");
        assertRemainingOfTenSeconds(larger.request());
        assertRemainingOfTenSeconds(invalid.request());
        assertRemainingOfTenSeconds(two.request());
        assertThat(header(unbound.request(), HEADER)).as("with no deadline bound, unchanged (OUT-04)").containsExactly("abc");
        for (Exchange exchange : List.of(smaller, larger, invalid, two, unbound)) {
            assertThat(exchange.violations()).isEmpty();
        }
    }

    @Test
    @DisplayName("DL-05 WIRE-04 applies to the value written")
    void wire04AppliesToTheValueWritten() {
        Exchange library = exchange(BoundrailClients.internal("orders"), tenSeconds(), NO_HEADERS, "boundrail.context.wire.max-value-length=2");
        Exchange application = exchange(BoundrailClients.internal("orders"), tenSeconds(), headers -> headers.set(HEADER, "100"),
                "boundrail.context.wire.max-value-length=2");

        for (Exchange exchange : List.of(library, application)) {
            assertThat(header(exchange.request(), HEADER)).isEmpty();
            assertThat(exchange.violations()).extracting(Violation::kind, Violation::key, Violation::boundary)
                    .containsExactly(tuple(Violation.Kind.OVERSIZED_VALUE, "boundrail.deadline", Boundary.INTERNAL_CALL));
        }
    }
}
