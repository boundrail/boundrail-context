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
import io.github.boundrail.context.ContextBinding;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.ContextViolationException;
import io.github.boundrail.context.Deadline;
import io.github.boundrail.context.DeadlineExceededException;
import io.github.boundrail.context.Destination;
import io.github.boundrail.context.DestinationClassifier;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.spring.support.RecordingServer;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/** The {@code Authorization} header the library's interceptor writes to internal calls from the bound authority (ID-03, ID-04). */
class AuthorityPropagationTest {

    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);
    private static final Authority ALICE = Authority.of("alice", "secret-token");
    private static final Consumer<RestClient.Builder> UNDECLARED = builder -> { };
    private static final Consumer<HttpHeaders> NO_HEADERS = headers -> { };

    /**
     * Classifies a declared request by its declaration and any other as {@code INTERNAL} service {@code orders}, so a {@code RestTemplate},
     * which has no declaration, can make internal calls to the loopback server.
     */
    private static final DestinationClassifier INTERNAL_BY_DEFAULT =
            request -> request.declared().orElse(new Destination(INTERNAL, Optional.of("orders")));

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class, RestClientAutoConfiguration.class,
                    RestTemplateAutoConfiguration.class));

    /** One call to a propagator. */
    private record Call(Authority authority, Destination destination) {
    }

    /** A propagator that records each call and answers with {@code answer}. */
    private static final class RecordingPropagator implements AuthorityPropagator {

        private final BiFunction<Authority, Destination, Optional<String>> answer;
        private final List<Call> calls = new CopyOnWriteArrayList<>();

        RecordingPropagator(BiFunction<Authority, Destination, Optional<String>> answer) {
            this.answer = answer;
        }

        @Override
        public Optional<String> outboundCredential(Authority authority, Destination destination) {
            calls.add(new Call(authority, destination));
            return answer.apply(authority, destination);
        }
    }

    private static RecordingPropagator forwarding() {
        return new RecordingPropagator((authority, destination) -> Optional.of(authority.credential()));
    }

    @Test
    @DisplayName("ID-03 an internal call carries Authorization: Bearer <credential>, and external and unknown calls carry none")
    void anInternalCallCarriesTheCredentialAndExternalAndUnknownCallsCarryNone() {
        RecordingPropagator propagator = forwarding();
        try (RecordingServer server = RecordingServer.start("ok")) {
            runner.withBean(AuthorityPropagator.class, () -> propagator).run(context -> {
                RestClient.Builder builder = context.getBean(RestClient.Builder.class).baseUrl(server.uri());
                RestClient internal = builder.clone().apply(BoundrailClients.internal("orders")).build();
                RestClient external = builder.clone().apply(BoundrailClients.external()).build();
                RestClient unknown = builder.clone().apply(UNDECLARED).build();

                Context.with(BuiltInKeys.AUTHORITY, ALICE).run(() -> {
                    get(internal, NO_HEADERS);
                    get(external, NO_HEADERS);
                    get(unknown, NO_HEADERS);
                });
            });
            assertThat(server.requests()).hasSize(3);
            assertThat(header(server.requests().get(0), "Authorization")).as("internal").containsExactly("Bearer secret-token");
            assertThat(header(server.requests().get(1), "Authorization")).as("external").isEmpty();
            assertThat(header(server.requests().get(2), "Authorization")).as("unknown").isEmpty();
            assertThat(propagator.calls).as("called for the internal call only")
                    .containsExactly(new Call(ALICE, new Destination(INTERNAL, Optional.of("orders"))));
        }
        try (RecordingServer server = RecordingServer.start("ok")) {
            runner.withBean(DestinationClassifier.class, () -> INTERNAL_BY_DEFAULT).run(context -> {
                RestTemplate template = context.getBean(RestTemplateBuilder.class).build();

                Context.with(BuiltInKeys.AUTHORITY, ALICE).run(() -> template.getForObject(server.uri(), String.class));
            });
            assertThat(header(server.requests().getFirst(), "Authorization")).as("RestTemplate, internal")
                    .containsExactly("Bearer secret-token");
        }
    }

    @Test
    @DisplayName("ID-03 with no AUTHORITY bound the propagator is not called and no Authorization is written")
    void withNoAuthorityBoundThePropagatorIsNotCalledAndNoAuthorizationIsWritten() {
        RecordingPropagator propagator = forwarding();
        try (RecordingServer server = RecordingServer.start("ok")) {
            runner.withBean(AuthorityPropagator.class, () -> propagator).run(context -> {
                RestClient internal = context.getBean(RestClient.Builder.class).baseUrl(server.uri())
                        .apply(BoundrailClients.internal("orders")).build();

                Context.with(TENANT, "acme").run(() -> get(internal, NO_HEADERS));
                assertThat(propagator.calls).as("no authority").isEmpty();

                Context.with(BuiltInKeys.AUTHORITY, ALICE).run(() -> get(internal, NO_HEADERS));
                assertThat(propagator.calls).as("authority bound")
                        .containsExactly(new Call(ALICE, new Destination(INTERNAL, Optional.of("orders"))));
            });
            assertThat(header(server.requests().get(0), "Authorization")).as("no authority").isEmpty();
            assertThat(header(server.requests().get(1), "Authorization")).as("authority bound").containsExactly("Bearer secret-token");
        }
    }

    @Test
    @DisplayName("ID-03 an application-set Authorization is left unchanged and the propagator is not called, and a name with no values is "
            + "unset")
    void anApplicationSetAuthorizationIsLeftUnchangedAndThePropagatorIsNotCalled() {
        RecordingPropagator propagator = forwarding();
        try (RecordingServer server = RecordingServer.start("ok")) {
            runner.withBean(AuthorityPropagator.class, () -> propagator).run(context -> {
                RestClient internal = context.getBean(RestClient.Builder.class).baseUrl(server.uri())
                        .apply(BoundrailClients.internal("orders")).build();

                Context.with(BuiltInKeys.AUTHORITY, ALICE).run(() -> {
                    get(internal, headers -> headers.set("Authorization", "Bearer app"));
                    assertThat(propagator.calls).as("Bearer app").isEmpty();
                    get(internal, headers -> headers.set("authorization", "x"));
                    assertThat(propagator.calls).as("lower-case name").isEmpty();
                    get(internal, headers -> headers.put("Authorization", new ArrayList<>()));
                    assertThat(propagator.calls).as("name with no values").hasSize(1);
                    get(internal, headers -> headers.set("Authorization", ""));
                    assertThat(propagator.calls).as("empty string").hasSize(1);
                    get(internal, NO_HEADERS);
                    assertThat(propagator.calls).as("nothing set").hasSize(2);
                });
            });
            List<RecordingServer.Request> requests = server.requests();
            assertThat(requests).hasSize(5);
            assertThat(header(requests.get(0), "Authorization")).as("Bearer app").containsExactly("Bearer app");
            assertThat(header(requests.get(1), "Authorization")).as("lower-case name").containsExactly("x");
            assertThat(header(requests.get(2), "Authorization")).as("name with no values").containsExactly("Bearer secret-token");
            assertThat(header(requests.get(3), "Authorization")).as("\"\" is a value").containsExactly("");
            assertThat(header(requests.get(4), "Authorization")).as("nothing set").containsExactly("Bearer secret-token");
        }
    }

    @Test
    @DisplayName("ID-03 a propagator that returns empty writes no Authorization and reports nothing")
    void aPropagatorThatReturnsEmptyWritesNoAuthorizationAndReportsNothing() {
        RecordingPropagator propagator = new RecordingPropagator((authority, destination) -> Optional.empty());
        List<Violation> violations = new CopyOnWriteArrayList<>();
        try (RecordingServer server = RecordingServer.start("ok")) {
            recording(violations).withBean(AuthorityPropagator.class, () -> propagator).run(context -> {
                RestClient internal = context.getBean(RestClient.Builder.class).baseUrl(server.uri())
                        .apply(BoundrailClients.internal("orders")).build();

                Context.with(BuiltInKeys.AUTHORITY, ALICE).run(() -> get(internal, NO_HEADERS));
            });
            assertThat(propagator.calls).containsExactly(new Call(ALICE, new Destination(INTERNAL, Optional.of("orders"))));
            assertThat(server.requests()).hasSize(1);
            assertThat(header(server.requests().getFirst(), "Authorization")).isEmpty();
            assertThat(violations).isEmpty();
        }
    }

    @Test
    @DisplayName("ID-03 the propagator is called after the required-key check and after the deadline check")
    void thePropagatorIsCalledAfterTheRequiredKeyCheckAndAfterTheDeadlineCheck() {
        ContextPolicy requiredTenant = ContextPolicy.builder()
                .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("Tenant-Id").required().propagateTo(INTERNAL_CALL))
                .build();
        ContextBinding expired = Context.with(BuiltInKeys.AUTHORITY, ALICE).with(TENANT, "acme")
                .with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ZERO));
        RecordingPropagator propagator = forwarding();
        try (RecordingServer server = RecordingServer.start("ok")) {
            runner.withBean(ContextPolicy.class, () -> requiredTenant).withBean(AuthorityPropagator.class, () -> propagator).run(context -> {
                RestClient internal = context.getBean(RestClient.Builder.class).baseUrl(server.uri())
                        .apply(BoundrailClients.internal("orders")).build();

                Throwable missing = catchThrowable(() -> Context.with(BuiltInKeys.AUTHORITY, ALICE).run(() -> get(internal, NO_HEADERS)));
                assertThat(missing).isInstanceOf(ContextViolationException.class);
                assertThat(propagator.calls).as("required key unbound, fail mode").isEmpty();

                Throwable late = catchThrowable(() -> expired.run(() -> get(internal, NO_HEADERS)));
                assertThat(late).isInstanceOf(DeadlineExceededException.class);
                assertThat(((DeadlineExceededException) late).stage()).isEqualTo(DeadlineExceededException.Stage.BEFORE_SEND);
                assertThat(propagator.calls).as("deadline expired").isEmpty();
            });
            assertThat(server.requests()).isEmpty();
        }
        try (RecordingServer server = RecordingServer.start("ok")) {
            runner.withPropertyValues("boundrail.context.violations.mode=report")
                    .withBean(ContextPolicy.class, () -> requiredTenant)
                    .run(context -> {
                        RestClient internal = context.getBean(RestClient.Builder.class).baseUrl(server.uri())
                                .apply(BoundrailClients.internal("orders")).build();

                        Context.with(BuiltInKeys.AUTHORITY, ALICE).run(() -> get(internal, NO_HEADERS));
                    });
            assertThat(header(server.requests().getFirst(), "Authorization")).as("required key unbound, report mode")
                    .containsExactly("Bearer secret-token");
        }
    }

    @Test
    @DisplayName("ID-03 a credential longer than wire.max-value-length is sent")
    void aCredentialLongerThanMaxValueLengthIsSent() {
        String credential = "a".repeat(1000);
        List<Violation> violations = new CopyOnWriteArrayList<>();
        try (RecordingServer server = RecordingServer.start("ok")) {
            recording(violations).run(context -> {
                RestClient internal = context.getBean(RestClient.Builder.class).baseUrl(server.uri())
                        .apply(BoundrailClients.internal("orders")).build();

                Context.with(BuiltInKeys.AUTHORITY, Authority.of("alice", credential)).run(() -> get(internal, NO_HEADERS));
            });
            assertThat(header(server.requests().getFirst(), "Authorization")).containsExactly("Bearer " + credential);
            assertThat(violations).isEmpty();
        }
    }

    @Test
    @DisplayName("ID-04 the forward propagator is the default, and an AuthorityPropagator bean replaces it")
    void theForwardPropagatorIsTheDefaultAndAnAuthorityPropagatorBeanReplacesIt() {
        assertThat(internalAuthorization(runner)).as("no bean").containsExactly("Bearer secret-token");

        ApplicationContextRunner replaced = runner.withBean(AuthorityPropagator.class,
                () -> (authority, destination) -> Optional.of("other"));
        assertThat(internalAuthorization(replaced)).as("a bean").containsExactly("Bearer other");
        assertThat(internalAuthorization(replaced.withPropertyValues("boundrail.context.authority.propagation=exchange")))
                .as("a bean, exchange selected, no exchange properties").containsExactly("Bearer other");

        try (RecordingServer server = RecordingServer.start("ok")) {
            replaced.withBean(DestinationClassifier.class, () -> INTERNAL_BY_DEFAULT).run(context -> {
                RestTemplate template = context.getBean(RestTemplateBuilder.class).build();

                Context.with(BuiltInKeys.AUTHORITY, ALICE).run(() -> template.getForObject(server.uri(), String.class));
            });
            assertThat(header(server.requests().getFirst(), "Authorization")).as("a bean, RestTemplate").containsExactly("Bearer other");
        }
    }

    @Test
    @DisplayName("CORE-16 the forward propagator rejects a null authority or destination with NullPointerException")
    void theForwardPropagatorRejectsANullAuthorityOrDestination() {
        runner.run(context -> {
            AuthorityPropagator propagator = context.getBean(AuthorityPropagator.class);
            Destination orders = new Destination(INTERNAL, Optional.of("orders"));

            assertThatNullPointerException().isThrownBy(() -> propagator.outboundCredential(null, orders));
            assertThatNullPointerException().isThrownBy(() -> propagator.outboundCredential(ALICE, null));
        });
    }

    @Test
    @DisplayName("ID-04 with several AuthorityPropagator beans the @Primary one is used, and with none primary startup fails where HTTP "
            + "client support is active, also with lazy initialization")
    void withSeveralAuthorityPropagatorBeansThePrimaryIsUsedAndWithNonePrimaryStartupFails() {
        ApplicationContextRunner primary = runner
                .withBean("firstPropagator", AuthorityPropagator.class, () -> (authority, destination) -> Optional.of("first"))
                .withBean("primaryPropagator", AuthorityPropagator.class, () -> (authority, destination) -> Optional.of("primary"),
                        definition -> definition.setPrimary(true));
        assertThat(internalAuthorization(primary)).containsExactly("Bearer primary");

        ApplicationContextRunner nonePrimary = runner
                .withBean("firstPropagator", AuthorityPropagator.class, () -> (authority, destination) -> Optional.of("first"))
                .withBean("secondPropagator", AuthorityPropagator.class, () -> (authority, destination) -> Optional.of("second"));
        ApplicationContextRunner lazy = nonePrimary.withPropertyValues("spring.main.lazy-initialization=true")
                .withInitializer(context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));
        for (ApplicationContextRunner configured : List.of(nonePrimary, lazy)) {
            configured.run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().isInstanceOf(NoUniqueBeanDefinitionException.class);
            });
            configured.withClassLoader(new FilteredClassLoader(RestClientCustomizer.class))
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    /** {@return the runner, with Micrometer hidden so that a counter recording into {@code violations} is the only one} */
    private ApplicationContextRunner recording(List<Violation> violations) {
        return runner.withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                .withBean(ViolationReporter.Counter.class, () -> violations::add);
    }

    /** {@return the {@code Authorization} values of one internal call to {@code orders} made with {@code ALICE} bound} */
    private static List<String> internalAuthorization(ApplicationContextRunner configured) {
        try (RecordingServer server = RecordingServer.start("ok")) {
            configured.run(context -> {
                RestClient internal = context.getBean(RestClient.Builder.class).baseUrl(server.uri())
                        .apply(BoundrailClients.internal("orders")).build();

                Context.with(BuiltInKeys.AUTHORITY, ALICE).run(() -> get(internal, NO_HEADERS));
            });
            assertThat(server.requests()).hasSize(1);
            return header(server.requests().getFirst(), "Authorization");
        }
    }

    private static void get(RestClient client, Consumer<HttpHeaders> applicationHeaders) {
        client.get().uri("/").headers(applicationHeaders).retrieve().toBodilessEntity();
    }

    /** {@return every value the server received under the header name, compared ignoring case} */
    private static List<String> header(RecordingServer.Request request, String name) {
        return request.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .toList();
    }
}
