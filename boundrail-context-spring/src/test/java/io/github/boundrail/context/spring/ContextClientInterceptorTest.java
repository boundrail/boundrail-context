package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Boundary.ATTACHED;
import static io.github.boundrail.context.Boundary.DETACHED;
import static io.github.boundrail.context.Boundary.EXTERNAL_CALL;
import static io.github.boundrail.context.Boundary.INTERNAL_CALL;
import static io.github.boundrail.context.Boundary.MESSAGE;
import static io.github.boundrail.context.Violation.Kind.INVALID_VALUE;
import static io.github.boundrail.context.Violation.Kind.OVERSIZED_VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import io.github.boundrail.context.Authority;
import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextBinding;
import io.github.boundrail.context.ContextCodec;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.Deadline;
import io.github.boundrail.context.Destination;
import io.github.boundrail.context.Initiator;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.spring.support.RecordingServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class ContextClientInterceptorTest {

    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);
    private static final ContextKey<Long> PRIORITY = ContextKey.of("priority", Long.class);
    private static final ContextKey<Locale> LOCALE = ContextKey.of("locale", Locale.class);
    private static final ContextKey<String> REGION = ContextKey.of("region", String.class);
    private static final ContextKey<String> LANGUAGE = ContextKey.of("language", String.class, "en");
    private static final ContextKey<String> AUDIT = ContextKey.of("audit", String.class);
    private static final ContextKey<String> CHANNEL = ContextKey.of("channel", String.class);
    private static final ContextKey<String> UNREGISTERED = ContextKey.of("unregistered", String.class);

    /** Application keys: internal-only, attached-only and external keys, each with a wire name. */
    private static final ContextPolicy POLICY = ContextPolicy.builder()
            .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("Tenant-Id").propagateTo(ATTACHED, INTERNAL_CALL))
            .key(PRIORITY, rules -> rules.codec(Codecs.longValue()).wireName("Priority").propagateTo(ATTACHED, INTERNAL_CALL))
            .key(LOCALE, rules -> rules.codec(Codecs.locale()).wireName("Locale").propagateTo(ATTACHED, INTERNAL_CALL))
            .key(REGION, rules -> rules.codec(Codecs.string()).wireName("Region").propagateTo(ATTACHED, INTERNAL_CALL))
            .key(LANGUAGE, rules -> rules.codec(Codecs.string()).wireName("Language").propagateTo(ATTACHED, INTERNAL_CALL))
            .key(AUDIT, rules -> rules.codec(Codecs.string()).wireName("Audit").propagateTo(ATTACHED))
            .key(CHANNEL, rules -> rules.codec(Codecs.string()).wireName("Channel").propagateTo(ATTACHED, EXTERNAL_CALL))
            .build();

    /** The test topology's application policy (001-foundation). */
    private static final ContextPolicy TOPOLOGY_POLICY = ContextPolicy.builder()
            .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("Tenant-Id").required().loggable()
                    .propagateTo(ATTACHED, DETACHED, INTERNAL_CALL, MESSAGE))
            .key(PRIORITY, rules -> rules.codec(Codecs.longValue()).wireName("Priority").propagateTo(ATTACHED, DETACHED, INTERNAL_CALL))
            .build();

    private static final Consumer<RestClient.Builder> UNDECLARED = builder -> { };
    private static final Consumer<HttpHeaders> NO_HEADERS = headers -> { };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class,
                    RestClientAutoConfiguration.class, RestTemplateAutoConfiguration.class));

    @Test
    @DisplayName("OUT-04 an internal call carries the bound keys that propagate to INTERNAL_CALL")
    void anInternalCallCarriesTheBoundKeysThatPropagateToInternalCall() {
        RecordingServer.Request request = send(POLICY, BoundrailClients.internal("orders"),
                Context.with(TENANT, "acme").with(PRIORITY, 5L), NO_HEADERS);

        assertThat(header(request, "Tenant-Id")).containsExactly("acme");
        assertThat(header(request, "Priority")).containsExactly("5");
    }

    @Test
    @DisplayName("WIRE-01 a key is sent in the header named by its wire name, encoded by its codec")
    void aKeyIsSentInTheHeaderNamedByItsWireNameEncodedByItsCodec() {
        RecordingServer.Request request = send(POLICY, BoundrailClients.internal("orders"),
                Context.with(TENANT, "acme").with(PRIORITY, 5L).with(LOCALE, Locale.forLanguageTag("pt-BR")), NO_HEADERS);

        assertThat(header(request, "Tenant-Id")).containsExactly("acme");
        assertThat(header(request, "Priority")).containsExactly("5");
        assertThat(header(request, "Locale")).as("the codec's encoding, not toString()").containsExactly("pt-BR");
        assertThat(header(request, "tenant")).as("the key name is not the header name").isEmpty();
    }

    @Test
    @DisplayName("WIRE-02 an internal call carries the initiator's value in Boundrail-Initiator")
    void anInternalCallCarriesTheInitiatorsValueInBoundrailInitiator() {
        RecordingServer.Request request = send(POLICY, BoundrailClients.internal("orders"),
                Context.with(BuiltInKeys.INITIATOR, Initiator.of("user:alice")), NO_HEADERS);

        assertThat(header(request, "Boundrail-Initiator")).containsExactly("user:alice");
    }

    @Test
    @DisplayName("OUT-04 an internal call writes no Authorization and no Boundrail-Deadline-Ms")
    void anInternalCallWritesNoAuthorizationAndNoBoundrailDeadlineMs() {
        RecordingServer.Request request = send(POLICY, BoundrailClients.internal("orders"),
                Context.with(TENANT, "acme")
                        .with(BuiltInKeys.AUTHORITY, Authority.of("alice", "secret-token"))
                        .with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ofMinutes(1))),
                NO_HEADERS);

        assertThat(header(request, "Tenant-Id")).containsExactly("acme");
        assertThat(header(request, "Authorization")).isEmpty();
        assertThat(header(request, "Boundrail-Deadline-Ms")).isEmpty();
    }

    @Test
    @DisplayName("OUT-04 unbound keys, keys with only a default, unregistered keys and keys not propagating to INTERNAL_CALL are not sent")
    void keysThatAreNotBoundOrDoNotPropagateToInternalCallAreNotSent() {
        RecordingServer.Request request = send(POLICY, BoundrailClients.internal("orders"),
                Context.with(TENANT, "acme").with(UNREGISTERED, "x").with(AUDIT, "audit-1").with(CHANNEL, "web"), NO_HEADERS);

        assertThat(header(request, "Tenant-Id")).containsExactly("acme");
        assertThat(header(request, "Region")).as("unbound").isEmpty();
        assertThat(header(request, "Language")).as("only a default").isEmpty();
        assertThat(header(request, "Audit")).as("propagates to ATTACHED only").isEmpty();
        assertThat(header(request, "Channel")).as("propagates to EXTERNAL_CALL only").isEmpty();
        assertThat(header(request, "Boundrail-Initiator")).as("unbound built-in key").isEmpty();
    }

    @Test
    @DisplayName("OUT-04 an external call carries only keys that propagate to EXTERNAL_CALL")
    void anExternalCallCarriesOnlyKeysThatPropagateToExternalCall() {
        RecordingServer.Request application = send(POLICY, BoundrailClients.external(),
                Context.with(TENANT, "acme").with(PRIORITY, 5L).with(CHANNEL, "web")
                        .with(BuiltInKeys.INITIATOR, Initiator.of("user:alice")),
                NO_HEADERS);

        assertThat(header(application, "Channel")).containsExactly("web");
        assertThat(header(application, "Tenant-Id")).isEmpty();
        assertThat(header(application, "Priority")).isEmpty();
        assertThat(header(application, "Boundrail-Initiator")).isEmpty();

        RecordingServer.Request topology = send(TOPOLOGY_POLICY, BoundrailClients.external(),
                Context.with(TENANT, "acme").with(PRIORITY, 5L)
                        .with(BuiltInKeys.INITIATOR, Initiator.of("user:alice"))
                        .with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ofMinutes(1))),
                NO_HEADERS);

        assertThat(header(topology, "Tenant-Id")).isEmpty();
        assertThat(header(topology, "Priority")).isEmpty();
        assertThat(header(topology, "Boundrail-Initiator")).isEmpty();
        assertThat(header(topology, "Boundrail-Deadline-Ms")).isEmpty();
    }

    @Test
    @DisplayName("DEST-03 an UNKNOWN destination is written like EXTERNAL")
    void anUnknownDestinationIsWrittenLikeExternal() {
        // No declaration and no host pattern: the default classifier answers UNKNOWN for the loopback server.
        RecordingServer.Request request = send(POLICY, UNDECLARED,
                Context.with(TENANT, "acme").with(PRIORITY, 5L).with(CHANNEL, "web")
                        .with(BuiltInKeys.INITIATOR, Initiator.of("user:alice")),
                NO_HEADERS);

        assertThat(header(request, "Channel")).containsExactly("web");
        assertThat(header(request, "Tenant-Id")).isEmpty();
        assertThat(header(request, "Priority")).isEmpty();
        assertThat(header(request, "Boundrail-Initiator")).isEmpty();
    }

    @Test
    @DisplayName("OUT-04 a wire header set by application code on an internal call is left unchanged")
    void aWireHeaderSetByApplicationCodeOnAnInternalCallIsLeftUnchanged() {
        RecordingServer.Request request = send(POLICY, BoundrailClients.internal("orders"),
                Context.with(TENANT, "acme").with(PRIORITY, 5L), headers -> headers.set("Tenant-Id", "mine"));

        assertThat(header(request, "Tenant-Id")).containsExactly("mine");
        assertThat(header(request, "Priority")).containsExactly("5");
    }

    @Test
    @DisplayName("WIRE-06 the application-set check ignores the header name's case")
    void theApplicationSetCheckIgnoresTheHeaderNamesCase() {
        RecordingServer.Request request = send(POLICY, BoundrailClients.internal("orders"),
                Context.with(TENANT, "acme").with(PRIORITY, 5L), headers -> headers.set("tenant-id", "mine"));

        assertThat(header(request, "Tenant-Id")).containsExactly("mine");
        assertThat(header(request, "Priority")).containsExactly("5");
    }

    static Stream<RuntimeException> encodeExceptions() {
        return Stream.of(new IllegalStateException("codec bug"), new IllegalArgumentException("codec rejects value"));
    }

    @ParameterizedTest
    @MethodSource("encodeExceptions")
    @DisplayName("OUT-04 an exception thrown by a codec's encode propagates unchanged and no violation is reported")
    void anExceptionThrownByACodecsEncodePropagatesUnchangedAndNoViolationIsReported(RuntimeException exception) {
        ContextKey<String> broken = ContextKey.of("broken", String.class);
        ContextCodec<String> throwing = new ContextCodec<>() {
            @Override
            public String encode(String value) {
                throw exception;
            }

            @Override
            public String decode(String text) {
                return text;
            }
        };
        ContextPolicy policy = ContextPolicy.builder()
                .key(broken, rules -> rules.codec(throwing).wireName("Broken").propagateTo(INTERNAL_CALL))
                .build();

        try (RecordingServer server = RecordingServer.start("ok")) {
            runner.withBean(ContextPolicy.class, () -> policy).withBean(SimpleMeterRegistry.class).run(context -> {
                RestClient client = context.getBean(RestClient.Builder.class).baseUrl(server.uri())
                        .apply(BoundrailClients.internal("orders")).build();

                Throwable thrown = catchThrowable(() -> Context.with(broken, "value")
                        .run(() -> client.get().uri("/").retrieve().toBodilessEntity()));

                assertThat(thrown).isSameAs(exception);
                assertThat(server.requests()).isEmpty();
                assertThat(context.getBean(MeterRegistry.class).find("boundrail.context.violations").meters()).isEmpty();
            });
        }
    }

    @Test
    @DisplayName("WIRE-04 an encoded value longer than max-value-length is not sent and is reported as OVERSIZED_VALUE")
    void anEncodedValueLongerThanMaxValueLengthIsNotSentAndIsReportedAsOversizedValue(CapturedOutput output) {
        String oversized = "acme-" + "9".repeat(252);

        Exchange exchange = exchange(POLICY, BoundrailClients.internal("orders"),
                Context.with(TENANT, oversized).with(PRIORITY, 5L), NO_HEADERS);

        assertThat(oversized).hasSize(257);
        assertThat(header(exchange.request(), "Tenant-Id")).isEmpty();
        assertThat(header(exchange.request(), "Priority")).as("the request is sent with the other keys").containsExactly("5");
        assertThat(exchange.violations()).containsExactly(new Violation(OVERSIZED_VALUE, "tenant", INTERNAL_CALL,
                Optional.of(new Destination(Destination.Kind.INTERNAL, Optional.of("orders"))), Thread.currentThread().getName(),
                Optional.empty(), Optional.empty()));
        assertThat(output.getOut()).contains("OVERSIZED_VALUE").doesNotContain("acme-").doesNotContain("9999999999");
    }

    @Test
    @DisplayName("WIRE-04 a value of exactly max-value-length is sent")
    void aValueOfExactlyMaxValueLengthIsSent() {
        String atLimit = "9".repeat(256);

        Exchange exchange = exchange(POLICY, BoundrailClients.internal("orders"), Context.with(TENANT, atLimit), NO_HEADERS);

        assertThat(header(exchange.request(), "Tenant-Id")).containsExactly(atLimit);
        assertThat(exchange.violations()).isEmpty();
    }

    @Test
    @DisplayName("WIRE-04 max-value-length is configurable and defaults to 256")
    void maxValueLengthIsConfigurableAndDefaultsTo256() {
        Exchange limited = exchange(POLICY, BoundrailClients.internal("orders"),
                Context.with(TENANT, "0123456789").with(REGION, "0123456789a"), NO_HEADERS,
                "boundrail.context.wire.max-value-length=10");

        assertThat(header(limited.request(), "Tenant-Id")).containsExactly("0123456789");
        assertThat(header(limited.request(), "Region")).isEmpty();
        assertThat(limited.violations()).extracting(Violation::kind, Violation::key).containsExactly(tuple(OVERSIZED_VALUE, "region"));

        Exchange byDefault = exchange(POLICY, BoundrailClients.internal("orders"),
                Context.with(TENANT, "1".repeat(256)).with(REGION, "2".repeat(257)), NO_HEADERS);

        assertThat(header(byDefault.request(), "Tenant-Id")).containsExactly("1".repeat(256));
        assertThat(header(byDefault.request(), "Region")).isEmpty();
        assertThat(byDefault.violations()).extracting(Violation::kind, Violation::key).containsExactly(tuple(OVERSIZED_VALUE, "region"));
    }

    @Test
    @DisplayName("WIRE-04 the length is counted in UTF-16 code units")
    void theLengthIsCountedInUtf16CodeUnits() {
        String emojis = "😀".repeat(6);

        Exchange exchange = exchange(POLICY, BoundrailClients.internal("orders"), Context.with(TENANT, emojis).with(PRIORITY, 5L),
                NO_HEADERS, "boundrail.context.wire.max-value-length=10");

        assertThat(emojis.codePointCount(0, emojis.length())).isEqualTo(6);
        assertThat(header(exchange.request(), "Tenant-Id")).isEmpty();
        assertThat(header(exchange.request(), "Priority")).containsExactly("5");
        assertThat(exchange.violations()).extracting(Violation::kind, Violation::key).containsExactly(tuple(OVERSIZED_VALUE, "tenant"));
    }

    @Test
    @DisplayName("OUT-04 a header name with no values counts as unset and the context value is written")
    void aHeaderNameWithNoValuesCountsAsUnsetAndTheContextValueIsWritten() {
        Exchange exchange = exchange(POLICY, BoundrailClients.internal("orders"), Context.with(TENANT, "acme").with(PRIORITY, 5L),
                headers -> {
                    headers.put("Tenant-Id", new ArrayList<>());
                    headers.set("Priority", "");
                });

        assertThat(header(exchange.request(), "Tenant-Id")).containsExactly("acme");
        assertThat(header(exchange.request(), "Priority")).as("\"\" is a value, so the header is set").containsExactly("");
    }

    @Test
    @DisplayName("OUT-04 an application-set header for a key that propagates to EXTERNAL_CALL is sent unchanged on an external call")
    void anApplicationSetHeaderForAKeyThatPropagatesToExternalCallIsSentUnchangedOnAnExternalCall() {
        Exchange exchange = exchange(POLICY, BoundrailClients.external(), Context.with(CHANNEL, "web"),
                headers -> headers.set("Channel", "mine"));

        assertThat(header(exchange.request(), "Channel")).containsExactly("mine");
    }

    static Stream<Arguments> applicationSetHeadersWithUncheckedContextValues() {
        ContextKey<String> broken = ContextKey.of("broken", String.class);
        ContextCodec<String> throwing = new ContextCodec<>() {
            @Override
            public String encode(String value) {
                throw new IllegalStateException("codec bug");
            }

            @Override
            public String decode(String text) {
                return text;
            }
        };
        ContextPolicy brokenPolicy = ContextPolicy.builder()
                .key(broken, rules -> rules.codec(throwing).wireName("Broken").propagateTo(INTERNAL_CALL))
                .build();
        return Stream.of(
                Arguments.of(Named.of("a codec whose encode throws", brokenPolicy), Context.with(broken, "value"), "Broken"),
                Arguments.of(Named.of("a 257-character tenant", POLICY), Context.with(TENANT, "9".repeat(257)), "Tenant-Id"),
                Arguments.of(Named.of("a tenant that is not a valid field value", POLICY), Context.with(TENANT, "a\nb"), "Tenant-Id"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("applicationSetHeadersWithUncheckedContextValues")
    @DisplayName("OUT-04 an application-set header's context value is not encoded or checked")
    void anApplicationSetHeadersContextValueIsNotEncodedOrChecked(ContextPolicy policy, ContextBinding binding, String wireName) {
        Exchange exchange = exchange(policy, BoundrailClients.internal("orders"), binding, headers -> headers.set(wireName, "mine"));

        assertThat(header(exchange.request(), wireName)).containsExactly("mine");
        assertThat(exchange.violations()).isEmpty();
    }

    static Stream<Named<String>> invalidFieldValues() {
        return Stream.of(
                Named.of("CR", "ac\rme"),
                Named.of("LF", "ac\nme"),
                Named.of("CRLF", "ac\r\nme"),
                Named.of("NUL", "ac\u0000me"),
                Named.of("DEL (U+007F)", "ac\u007Fme"),
                Named.of("\u00e9 (U+00E9)", "acm\u00E9"),
                Named.of("U+0080", "ac\u0080me"),
                Named.of("an emoji", "ac\uD83D\uDE00me"),
                Named.of("a leading space", " acme"),
                Named.of("a trailing tab", "acme\t"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("invalidFieldValues")
    @DisplayName("WIRE-08 a value that is not a valid field value is not written and is reported as INVALID_VALUE")
    void aValueThatIsNotAValidFieldValueIsNotWrittenAndIsReportedAsInvalidValue(String tenant) {
        Exchange exchange = exchange(POLICY, BoundrailClients.internal("orders"), Context.with(TENANT, tenant).with(PRIORITY, 5L),
                NO_HEADERS);

        assertThat(header(exchange.request(), "Tenant-Id")).isEmpty();
        assertThat(header(exchange.request(), "Priority")).as("the request is sent with the other keys").containsExactly("5");
        assertThat(exchange.violations()).containsExactly(new Violation(INVALID_VALUE, "tenant", INTERNAL_CALL,
                Optional.of(new Destination(Destination.Kind.INTERNAL, Optional.of("orders"))), Thread.currentThread().getName(),
                Optional.empty(), Optional.empty()));
    }

    @Test
    @DisplayName("WIRE-08 visible ASCII with inner spaces and tabs is written")
    void visibleAsciiWithInnerSpacesAndTabsIsWritten() {
        // The JDK HttpServer replaces a tab in a received header value with a space, so the written value is observed by an
        // interceptor that runs after the library's.
        List<String> written = new CopyOnWriteArrayList<>();
        Consumer<RestClient.Builder> observed = BoundrailClients.internal("orders").andThen(builder -> builder.requestInterceptor(
                (request, body, execution) -> {
                    written.addAll(request.getHeaders().getOrEmpty("Tenant-Id"));
                    return execution.execute(request, body);
                }));

        Exchange exchange = exchange(POLICY, observed, Context.with(TENANT, "a b\tc"), NO_HEADERS);

        assertThat(written).containsExactly("a b\tc");
        assertThat(header(exchange.request(), "Tenant-Id")).hasSize(1);
        assertThat(exchange.violations()).isEmpty();
    }

    @Test
    @DisplayName("WIRE-08 an empty value is written")
    void anEmptyValueIsWritten() {
        Exchange exchange = exchange(POLICY, BoundrailClients.internal("orders"), Context.with(TENANT, ""), NO_HEADERS);

        assertThat(header(exchange.request(), "Tenant-Id")).containsExactly("");
        assertThat(exchange.violations()).isEmpty();
    }

    @Test
    @DisplayName("WIRE-08 applies to Boundrail-Initiator")
    void appliesToBoundrailInitiator() {
        Exchange exchange = exchange(POLICY, BoundrailClients.internal("orders"),
                Context.with(BuiltInKeys.INITIATOR, Initiator.of("a\nb")).with(PRIORITY, 5L), NO_HEADERS);

        assertThat(header(exchange.request(), "Boundrail-Initiator")).isEmpty();
        assertThat(header(exchange.request(), "Priority")).containsExactly("5");
        assertThat(exchange.violations()).extracting(Violation::kind, Violation::key)
                .containsExactly(tuple(INVALID_VALUE, BuiltInKeys.INITIATOR.name()));
    }

    @Test
    @DisplayName("DIAG-09 an invalid outbound value is reported without the value")
    void anInvalidOutboundValueIsReportedWithoutTheValue(CapturedOutput output) {
        String invalid = "secr\u00E9t-tenant";

        Exchange exchange = exchange(POLICY, BoundrailClients.internal("orders"), Context.with(TENANT, invalid), NO_HEADERS);

        assertThat(exchange.violations()).extracting(Violation::kind, Violation::key).containsExactly(tuple(INVALID_VALUE, "tenant"));
        assertThat(exchange.violations().getFirst().toString()).doesNotContain(invalid).doesNotContain("secr");
        assertThat(output.getOut()).contains("INVALID_VALUE").doesNotContain(invalid).doesNotContain("secr");
    }

    @Test
    @DisplayName("WIRE-04 an outbound value both oversized and not a valid field value is reported once, as OVERSIZED_VALUE")
    void anOutboundValueBothOversizedAndNotAValidFieldValueIsReportedOnceAsOversizedValue() {
        String oversizedAndInvalid = "\n" + "9".repeat(256);

        Exchange exchange = exchange(POLICY, BoundrailClients.internal("orders"),
                Context.with(TENANT, oversizedAndInvalid).with(PRIORITY, 5L), NO_HEADERS);

        assertThat(oversizedAndInvalid).hasSize(257);
        assertThat(header(exchange.request(), "Tenant-Id")).isEmpty();
        assertThat(header(exchange.request(), "Priority")).containsExactly("5");
        assertThat(exchange.violations()).extracting(Violation::kind, Violation::key).containsExactly(tuple(OVERSIZED_VALUE, "tenant"));
    }

    @Test
    @DisplayName("WIRE-04 a non-ASCII value within max-value-length in UTF-16 code units but not in UTF-8 bytes is reported as INVALID_VALUE, "
            + "not OVERSIZED_VALUE")
    void aNonAsciiValueWithinMaxValueLengthInUtf16CodeUnitsButNotInUtf8BytesIsReportedAsInvalidValueNotOversizedValue() {
        String accents = "\u00E9".repeat(6);

        Exchange exchange = exchange(POLICY, BoundrailClients.internal("orders"), Context.with(TENANT, accents).with(PRIORITY, 5L),
                NO_HEADERS, "boundrail.context.wire.max-value-length=10");

        assertThat(accents).hasSize(6);
        assertThat(accents.getBytes(StandardCharsets.UTF_8)).hasSize(12);
        assertThat(header(exchange.request(), "Tenant-Id")).isEmpty();
        assertThat(header(exchange.request(), "Priority")).containsExactly("5");
        assertThat(exchange.violations()).extracting(Violation::kind, Violation::key).containsExactly(tuple(INVALID_VALUE, "tenant"));
    }

    static Stream<Arguments> maxValueLengthsBelowOne() {
        return Stream.of(
                Arguments.of("0", false),
                Arguments.of("-1", false),
                Arguments.of("0", true),
                Arguments.of("-1", true));
    }

    @ParameterizedTest(name = "max-value-length={0}, lazy initialization: {1}")
    @MethodSource("maxValueLengthsBelowOne")
    @DisplayName("WIRE-04 a max-value-length below 1 fails startup")
    void aMaxValueLengthBelowOneFailsStartup(String maxValueLength, boolean lazyInitialization) {
        ApplicationContextRunner configured = runner.withPropertyValues("boundrail.context.wire.max-value-length=" + maxValueLength);
        if (lazyInitialization) {
            configured = configured.withPropertyValues("spring.main.lazy-initialization=true")
                    .withInitializer(context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));
        }
        configured.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }

    /** The request the server received and the violations reported while sending it. */
    private record Exchange(RecordingServer.Request request, List<Violation> violations) {
    }

    /**
     * Sends one GET like {@link #send}, with the given properties, and records every reported violation. Micrometer is hidden so that the
     * recording counter is the only {@code ViolationReporter.Counter} bean.
     */
    private Exchange exchange(ContextPolicy policy, Consumer<RestClient.Builder> declaration, ContextBinding binding,
            Consumer<HttpHeaders> applicationHeaders, String... properties) {
        List<Violation> violations = new CopyOnWriteArrayList<>();
        try (RecordingServer server = RecordingServer.start("ok")) {
            runner.withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                    .withPropertyValues(properties)
                    .withBean(ContextPolicy.class, () -> policy)
                    .withBean(ViolationReporter.Counter.class, () -> violations::add)
                    .run(context -> {
                        RestClient client = context.getBean(RestClient.Builder.class).baseUrl(server.uri()).apply(declaration).build();

                        binding.run(() -> client.get().uri("/").headers(applicationHeaders).retrieve().toBodilessEntity());
                    });
            assertThat(server.requests()).hasSize(1);
            return new Exchange(server.requests().getFirst(), List.copyOf(violations));
        }
    }

    /** Sends one GET through the context's {@code RestClient.Builder}, inside the binding, and returns the request the server received. */
    private RecordingServer.Request send(ContextPolicy policy, Consumer<RestClient.Builder> declaration, ContextBinding binding,
            Consumer<HttpHeaders> applicationHeaders) {
        try (RecordingServer server = RecordingServer.start("ok")) {
            runner.withBean(ContextPolicy.class, () -> policy).run(context -> {
                RestClient client = context.getBean(RestClient.Builder.class).baseUrl(server.uri()).apply(declaration).build();

                binding.run(() -> client.get().uri("/").headers(applicationHeaders).retrieve().toBodilessEntity());
            });
            assertThat(server.requests()).hasSize(1);
            return server.requests().getFirst();
        }
    }

    /** {@return every value the server received under the header name, compared ignoring case} */
    private static List<String> header(RecordingServer.Request request, String name) {
        return request.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .toList();
    }
}
