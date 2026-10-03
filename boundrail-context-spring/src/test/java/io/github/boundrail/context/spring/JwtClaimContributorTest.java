package io.github.boundrail.context.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.ContextCodec;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.Violation;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class JwtClaimContributorTest {

    private static final ContextKey<Long> PRIORITY = ContextKey.of("priority", Long.class);
    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);

    /** Records every call, in order, as text. */
    static final class RecordingContributions implements ContextContributions {

        final List<String> calls = new ArrayList<>();

        @Override
        public <T> void put(ContextKey<T> key, T value) {
            calls.add("put " + key.name() + "=" + value + " (" + value.getClass().getSimpleName() + ")");
        }

        @Override
        public void invalid(ContextKey<?> key) {
            calls.add("invalid " + key.name());
        }
    }

    @Test
    @DisplayName("IN-06 the named claim of a JWT authentication is decoded with the codec and contributed")
    void theNamedClaimOfAJwtAuthenticationIsDecodedWithTheCodecAndContributed() {
        RecordingContributions contributions = new RecordingContributions();

        JwtClaimContributor.of("priority", PRIORITY, Codecs.longValue())
                .contribute(jwtAuthentication("priority", "5"), contributions);

        assertThat(contributions.calls).containsExactly("put priority=5 (Long)");
    }

    @Test
    @DisplayName("IN-06 a missing claim leaves the key unbound and reports nothing")
    void aMissingClaimLeavesTheKeyUnboundAndReportsNothing() {
        RecordingContributions contributions = new RecordingContributions();

        JwtClaimContributor.of("priority", PRIORITY, Codecs.longValue())
                .contribute(jwtAuthentication("tenant", "acme"), contributions);

        assertThat(contributions.calls).isEmpty();
    }

    @Test
    @DisplayName("IN-06 a non-JWT authentication contributes nothing")
    void aNonJwtAuthenticationContributesNothing() {
        RecordingContributions contributions = new RecordingContributions();
        // Its name, credentials and details all decode as a priority, so only the check for a JWT authentication keeps them out.
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated("5", "5", List.of());
        authentication.setDetails("5");

        JwtClaimContributor.of("priority", PRIORITY, Codecs.longValue()).contribute(authentication, contributions);

        assertThat(contributions.calls).isEmpty();
    }

    @Test
    @DisplayName("CORE-13 JwtClaimContributor rejects null")
    void jwtClaimContributorRejectsNull() {
        JwtClaimContributor contributor = JwtClaimContributor.of("priority", PRIORITY, Codecs.longValue());
        Authentication nonJwt = UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of());

        assertThatNullPointerException().as("null claim").isThrownBy(() -> JwtClaimContributor.of(null, PRIORITY, Codecs.longValue()));
        assertThatNullPointerException().as("null key").isThrownBy(() -> JwtClaimContributor.of("priority", null, Codecs.longValue()));
        assertThatNullPointerException().as("null codec").isThrownBy(() -> JwtClaimContributor.of("priority", PRIORITY, null));
        assertThatNullPointerException().as("null authentication")
                .isThrownBy(() -> contributor.contribute(null, new RecordingContributions()));
        assertThatNullPointerException().as("null contributions, JWT authentication")
                .isThrownBy(() -> contributor.contribute(jwtAuthentication("priority", "5"), null));
        assertThatNullPointerException().as("null contributions, non-JWT authentication")
                .isThrownBy(() -> contributor.contribute(nonJwt, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonStringClaims")
    @DisplayName("IN-06 a claim whose value is not a String is reported as INVALID_VALUE")
    void aClaimWhoseValueIsNotAStringIsReportedAsInvalidValue(Object value) {
        RecordingContributions contributions = new RecordingContributions();

        JwtClaimContributor.of("priority", PRIORITY, Codecs.longValue()).contribute(jwtAuthentication("priority", value), contributions);

        assertThat(contributions.calls).containsExactly("invalid priority");
    }

    static Stream<Object> nonStringClaims() {
        return Stream.of(5L, true, List.of("5"));
    }

    @Test
    @DisplayName("IN-06 a claim that fails to decode is reported as INVALID_VALUE")
    void aClaimThatFailsToDecodeIsReportedAsInvalidValue() {
        Filtered filtered = filter(JwtClaimContributor.of("priority", PRIORITY, Codecs.longValue()), jwtAuthentication("priority", "abc"));

        assertThat(filtered.priority()).as("the request continued, with priority unbound").isEmpty();
        assertThat(filtered.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.kind()).isEqualTo(Violation.Kind.INVALID_VALUE);
            assertThat(violation.key()).isEqualTo("priority");
            assertThat(violation.boundary()).isEqualTo(Boundary.INTERNAL_CALL);
            assertThat(violation.destination()).isEmpty();
        });
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("DIAG-09 an invalid claim is reported without the value or the codec's message")
    void anInvalidClaimIsReportedWithoutTheValueOrTheCodecsMessage(CapturedOutput output) {
        String value = "abc-claim-value";
        String codecMessage = catchThrowable(() -> Codecs.longValue().decode(value)).getMessage();
        assertThat(codecMessage).as("precondition: the codec's message echoes the value").contains(value);

        Filtered filtered = filter(JwtClaimContributor.of("priority", PRIORITY, Codecs.longValue()), jwtAuthentication("priority", value));

        assertThat(filtered.violations()).singleElement().satisfies(violation -> assertThat(violation.toString())
                .doesNotContain(value)
                .doesNotContain(codecMessage));
        List<String> entries = output.getOut().lines().filter(line -> line.contains("Context violation")).toList();
        assertThat(entries).anyMatch(line -> line.contains("INVALID_VALUE: key priority"))
                .noneMatch(line -> line.contains(value))
                .noneMatch(line -> line.contains(codecMessage));
    }

    @Test
    @DisplayName("IN-06 a claim value longer than max-value-length is bound")
    void aClaimValueLongerThanMaxValueLengthIsBound() {
        String value = "acme-" + "q".repeat(295);

        Filtered filtered = filter(JwtClaimContributor.of("tenant", TENANT, Codecs.string()), jwtAuthentication("tenant", value));

        assertThat(value).hasSize(300);
        assertThat(filtered.tenant()).contains(value);
        assertThat(filtered.violations()).isEmpty();
    }

    @Test
    @DisplayName("IN-06 any other exception from the claim's codec propagates unchanged")
    void anyOtherExceptionFromTheClaimsCodecPropagatesUnchanged() {
        IllegalStateException failure = new IllegalStateException("codec failure");
        ContextCodec<Long> failing = new ContextCodec<>() {
            @Override
            public String encode(Long value) {
                return value.toString();
            }

            @Override
            public Long decode(String text) {
                throw failure;
            }
        };
        RecordingContributions contributions = new RecordingContributions();

        Throwable thrown = catchThrowable(() -> JwtClaimContributor.of("priority", PRIORITY, failing)
                .contribute(jwtAuthentication("priority", "5"), contributions));

        assertThat(thrown).isSameAs(failure);
        assertThat(contributions.calls).isEmpty();
    }

    /** What the servlet at the end of the chain observed, and the violations reported while the request was filtered. */
    record Filtered(Optional<String> tenant, Optional<Long> priority, List<Violation> violations) {
    }

    /**
     * Sends a request with the given authentication through the authentication filter of a context in {@code fail} mode whose only contributor
     * is the given one, and records every reported violation. Micrometer is hidden so that the recording counter is the only
     * {@code ViolationReporter.Counter} bean.
     */
    private static Filtered filter(JwtClaimContributor contributor, Authentication authentication) {
        List<Violation> violations = new CopyOnWriteArrayList<>();
        AtomicReference<Filtered> filtered = new AtomicReference<>();
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                .withPropertyValues("boundrail.context.violations.mode=fail")
                .withBean(ViolationReporter.Counter.class, () -> violations::add)
                .withBean(JwtClaimContributor.class, () -> contributor)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Filter filter = context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                            .map(FilterRegistrationBean::getFilter)
                            .filter(ContextAuthenticationFilter.class::isInstance)
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("no FilterRegistrationBean for ContextAuthenticationFilter"));
                    AtomicReference<Filtered> observed = new AtomicReference<>();
                    HttpServlet recording = new HttpServlet() {
                        @Override
                        protected void service(HttpServletRequest req, HttpServletResponse resp) {
                            observed.set(new Filtered(TENANT.find(), PRIORITY.find(), List.of()));
                        }
                    };
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    try {
                        new MockFilterChain(recording, filter).doFilter(new MockHttpServletRequest(), new MockHttpServletResponse());
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                    assertThat(observed.get()).as("the chain ran").isNotNull();
                    filtered.set(observed.get());
                });
        return new Filtered(filtered.get().tenant(), filtered.get().priority(), List.copyOf(violations));
    }

    private static JwtAuthenticationToken jwtAuthentication(String claim, Object value) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("alice").claim(claim, value).build();
        return new JwtAuthenticationToken(jwt, List.of());
    }
}
