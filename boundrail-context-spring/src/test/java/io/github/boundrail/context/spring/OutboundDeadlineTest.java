package io.github.boundrail.context.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextBinding;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.DeadlineExceededException;
import io.github.boundrail.context.Deadline;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.spring.support.RecordingServer;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/** The remaining time the library's interceptor writes to internal calls as {@code Boundrail-Deadline-Ms} (DL-05). */
class OutboundDeadlineTest {

    private static final String HEADER = "Boundrail-Deadline-Ms";
    private static final Consumer<HttpHeaders> NO_HEADERS = headers -> { };

    /** Micrometer is hidden, so the recording counter is the only {@code ViolationReporter.Counter} bean. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class, RestClientAutoConfiguration.class,
                    RestTemplateAutoConfiguration.class))
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

    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);

    /** The test topology's required {@code tenant} (001-foundation). */
    private static final ContextPolicy REQUIRED_TENANT = ContextPolicy.builder()
            .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("Tenant-Id").required().propagateTo(Boundary.INTERNAL_CALL))
            .build();

    private static ContextBinding expired() {
        return Context.with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ZERO));
    }

    /** The requests the server received, the violations reported, and what the call threw, or {@code null}. */
    private record Attempt(List<RecordingServer.Request> requests, List<Violation> violations, Throwable thrown) {
    }

    /**
     * Sends one GET inside the binding, through a {@code RestClient} from the auto-configured builder or, with {@code restTemplate}, a
     * {@code RestTemplate} from the auto-configured {@code RestTemplateBuilder}; allows the call to throw and the server to receive nothing.
     */
    private Attempt attempt(boolean restTemplate, Consumer<RestClient.Builder> declaration, ContextPolicy policy, ContextBinding binding,
            String... properties) {
        List<Violation> violations = new CopyOnWriteArrayList<>();
        List<Violation> reported = new ArrayList<>();
        Throwable[] thrown = new Throwable[1];
        try (RecordingServer server = RecordingServer.start("ok")) {
            ApplicationContextRunner configured = runner.withPropertyValues(properties)
                    .withBean(ViolationReporter.Counter.class, () -> violations::add);
            if (policy != null) {
                configured = configured.withBean(ContextPolicy.class, () -> policy);
            }
            configured.run(context -> {
                Runnable call;
                if (restTemplate) {
                    RestTemplate template = context.getBean(RestTemplateBuilder.class).build();
                    call = () -> template.getForObject(server.uri(), String.class);
                } else {
                    RestClient client = context.getBean(RestClient.Builder.class).baseUrl(server.uri()).apply(declaration).build();
                    call = () -> client.get().uri("/").retrieve().toBodilessEntity();
                }
                thrown[0] = catchThrowable(() -> binding.run(call));
                reported.addAll(violations);
            });
            return new Attempt(List.copyOf(server.requests()), List.copyOf(reported), thrown[0]);
        }
    }

    private static void assertBeforeSend(Attempt attempt, String description) {
        assertThat(attempt.thrown()).as("%s: thrown unwrapped", description).isInstanceOf(DeadlineExceededException.class);
        assertThat(((DeadlineExceededException) attempt.thrown()).stage()).as(description)
                .isEqualTo(DeadlineExceededException.Stage.BEFORE_SEND);
        assertThat(attempt.requests()).as("%s: not sent", description).isEmpty();
    }

    @Test
    @DisplayName("DL-06 an expired deadline throws DeadlineExceededException at BEFORE_SEND and the request is not sent, to any destination")
    void anExpiredDeadlineThrowsAtBeforeSendAndTheRequestIsNotSent() {
        assertBeforeSend(attempt(false, BoundrailClients.internal("orders"), null, expired()), "internal");
        assertBeforeSend(attempt(false, BoundrailClients.external(), null, expired()), "external");
        assertBeforeSend(attempt(false, builder -> { }, null, expired()), "unknown");
        assertBeforeSend(attempt(true, builder -> { }, null, expired()), "RestTemplate");
    }

    @Test
    @DisplayName("DL-06 the expiry check comes before the required-key check, so no violation is reported and no header is written")
    void theExpiryCheckComesBeforeTheRequiredKeyCheck() {
        for (String mode : new String[] {"fail", "report"}) {
            Attempt attempt = attempt(false, BoundrailClients.internal("orders"), REQUIRED_TENANT, expired(),
                    "boundrail.context.violations.mode=" + mode);

            assertBeforeSend(attempt, mode);
            assertThat(attempt.violations()).as("%s: no MISSING_REQUIRED", mode).isEmpty();
        }
    }
}
