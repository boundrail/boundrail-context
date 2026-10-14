package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Boundary.INTERNAL_CALL;
import static io.github.boundrail.context.BuiltInKeys.AUTHORITY;
import static io.github.boundrail.context.BuiltInKeys.INITIATOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.boundrail.context.Authority;
import io.github.boundrail.context.ContextSnapshot;
import io.github.boundrail.context.Initiator;
import io.github.boundrail.context.Violation;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** {@code ContextAuthenticationFilter} derives {@code INITIATOR = user:<name>} for an authenticated request (ID-08). */
class InitiatorDerivationTest {

    private static final JwtAuthenticationToken ALICE_JWT = new JwtAuthenticationToken(
            Jwt.withTokenValue("alice-jwt").header("alg", "none").subject("alice").build(), List.of());
    private static final Authentication ALICE_PASSWORD = UsernamePasswordAuthenticationToken.authenticated("alice", "password", List.of());

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class));

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** What the servlet at the end of the chain observed. */
    record Observed(Optional<Initiator> initiator, Optional<Authority> authority) {
    }

    @Test
    @DisplayName("ID-08 an authenticated request binds INITIATOR user:<name>")
    void anAuthenticatedRequestBindsInitiatorUserName() {
        runner.run(context -> {
            Observed jwt = filter(ALICE_JWT, request -> { }, authenticationFilter(context));
            Observed password = filter(ALICE_PASSWORD, request -> { }, authenticationFilter(context));

            assertThat(jwt.initiator()).as("a JWT").contains(Initiator.of("user:alice"));
            assertThat(password.initiator()).as("username and password").contains(Initiator.of("user:alice"));
            assertThat(password.authority()).as("username and password bind no authority").isEmpty();
        });
    }

    @Test
    @DisplayName("ID-08 a trusted Boundrail-Initiator wins over a contribution, and a contribution over the derived value")
    void aTrustedHeaderWinsOverAContributionAndAContributionOverTheDerivedValue() {
        runner.withBean(InboundTrustVerifier.class, () -> request -> InboundTrustVerifier.Trust.TRUSTED_INTERNAL).run(context -> {
            Filter inbound = registration(context, ContextInboundFilter.class).getFilter();

            Observed neither = filter(ALICE_JWT, request -> { }, inbound, authenticationFilter(context));

            assertThat(neither.initiator()).as("neither: derived").contains(Initiator.of("user:alice"));
        });
        runner.withBean(InboundTrustVerifier.class, () -> request -> InboundTrustVerifier.Trust.TRUSTED_INTERNAL)
                .withBean(ContextContributor.class, () -> (authentication, contributions) ->
                        contributions.put(INITIATOR, Initiator.of("user:bob")))
                .run(context -> {
                    Filter inbound = registration(context, ContextInboundFilter.class).getFilter();

                    Observed both = filter(ALICE_JWT, request -> request.addHeader("Boundrail-Initiator", "service:edge"), inbound,
                            authenticationFilter(context));
                    Observed contributed = filter(ALICE_JWT, request -> { }, inbound, authenticationFilter(context));

                    assertThat(both.initiator()).as("trusted header and contribution").contains(Initiator.of("service:edge"));
                    assertThat(contributed.initiator()).as("contribution only").contains(Initiator.of("user:bob"));
                });
    }

    @Test
    @DisplayName("ID-08 a name longer than 251 characters leaves INITIATOR unbound, reports one INVALID_VALUE for boundrail.initiator with "
            + "boundary INTERNAL_CALL and no destination, and the request continues")
    void aNameLongerThan251CharactersLeavesInitiatorUnboundAndReportsOneInvalidValue() {
        String name251 = "a".repeat(251);
        String name252 = "b".repeat(252);

        Recorded tooLong = filterRecording(jwt(name252));
        Recorded longest = filterRecording(jwt(name251));

        assertThat(tooLong.observed().initiator()).as("252 characters").isEmpty();
        assertThat(tooLong.observed().authority()).as("252 characters: the authority").map(Authority::subject).contains(name252);
        assertThat(tooLong.violations()).as("252 characters").singleElement().satisfies(violation -> {
            assertThat(violation.kind()).isEqualTo(Violation.Kind.INVALID_VALUE);
            assertThat(violation.key()).isEqualTo("boundrail.initiator");
            assertThat(violation.boundary()).isEqualTo(INTERNAL_CALL);
            assertThat(violation.destination()).isEmpty();
            assertThat(violation.toString()).as("DIAG-09: no value").doesNotContain(name252);
        });
        assertThat(longest.observed().initiator()).as("251 characters").contains(Initiator.of("user:" + name251));
        assertThat(longest.violations()).as("251 characters").isEmpty();
    }

    @Test
    @DisplayName("ID-08 an empty name leaves INITIATOR unbound and reports one INVALID_VALUE for boundrail.initiator, while the authority is "
            + "still bound")
    void anEmptyNameLeavesInitiatorUnboundAndReportsOneInvalidValue() {
        JwtAuthenticationToken noSubject = new JwtAuthenticationToken(
                Jwt.withTokenValue("no-subject-jwt").header("alg", "none").claim("scope", "read").build(), List.of());

        Recorded recorded = filterRecording(noSubject);

        assertThat(recorded.observed().initiator()).isEmpty();
        assertThat(recorded.observed().authority()).as("the authority, with an empty subject").hasValueSatisfying(authority -> {
            assertThat(authority.subject()).isEmpty();
            assertThat(authority.credential()).isEqualTo("no-subject-jwt");
        });
        assertThat(recorded.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.kind()).isEqualTo(Violation.Kind.INVALID_VALUE);
            assertThat(violation.key()).isEqualTo("boundrail.initiator");
            assertThat(violation.boundary()).isEqualTo(INTERNAL_CALL);
            assertThat(violation.destination()).isEmpty();
        });
    }

    /** Returns a {@code Callable} that reports the bound initiator. */
    @RestController
    static final class CallableController {

        @GetMapping("/callable")
        Callable<String> callable() {
            return () -> INITIATOR.find().map(Initiator::value).orElse("-");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class Mvc {

        @Bean
        CallableController callableController() {
            return new CallableController();
        }
    }

    @Test
    @DisplayName("ID-08 the derived initiator is in the stored snapshot, and a Callable runs with it")
    void theDerivedInitiatorIsInTheStoredSnapshotAndACallableRunsWithIt() {
        runner.withUserConfiguration(Mvc.class).run(context -> {
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext())
                    .addFilters(authenticationFilter(context)).build();
            SecurityContextHolder.getContext().setAuthentication(ALICE_JWT);

            MvcResult started = mockMvc.perform(get("/callable")).andReturn();

            assertThat(started.getAsyncResult(5_000)).isEqualTo("user:alice");
            assertThat(((ContextSnapshot) started.getRequest()
                    .getAttribute(ContextInboundFilter.SNAPSHOT_ATTRIBUTE)).find(INITIATOR))
                    .as("the stored snapshot").contains(Initiator.of("user:alice"));
        });
    }

    /** What the servlet at the end of the chain observed, and the violations reported while the request was filtered. */
    record Recorded(Observed observed, List<Violation> violations) {
    }

    /** A verified JWT authentication whose subject, and so whose name, is {@code subject}. */
    private static JwtAuthenticationToken jwt(String subject) {
        return new JwtAuthenticationToken(Jwt.withTokenValue("jwt-" + subject.length()).header("alg", "none").subject(subject).build(),
                List.of());
    }

    /**
     * Sends a request with the given authentication through the authentication filter, and records every reported violation. Micrometer is
     * hidden so that the recording counter is the only {@code ViolationReporter.Counter} bean.
     */
    private Recorded filterRecording(Authentication authentication) {
        List<Violation> violations = new CopyOnWriteArrayList<>();
        AtomicReference<Observed> observed = new AtomicReference<>();
        runner.withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                .withBean(ViolationReporter.Counter.class, () -> violations::add)
                .run(context -> observed.set(filter(authentication, request -> { }, authenticationFilter(context))));
        assertThat(observed.get()).as("the chain ran").isNotNull();
        return new Recorded(observed.get(), List.copyOf(violations));
    }

    private static Filter authenticationFilter(AssertableWebApplicationContext context) {
        return registration(context, ContextAuthenticationFilter.class).getFilter();
    }

    private static FilterRegistrationBean<?> registration(AssertableWebApplicationContext context, Class<?> filterType) {
        assertThat(context).hasNotFailed();
        return context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .filter(registration -> filterType.isInstance(registration.getFilter()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no FilterRegistrationBean for " + filterType.getSimpleName()));
    }

    /**
     * Sends a request with the given headers through the filters to a servlet that records the bound initiator and authority, with the
     * given authentication in the {@code SecurityContextHolder}.
     */
    private static Observed filter(Authentication authentication, Consumer<MockHttpServletRequest> headers, Filter... filters)
            throws Exception {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(authentication);
        MockHttpServletRequest request = new MockHttpServletRequest();
        headers.accept(request);
        AtomicReference<Observed> observed = new AtomicReference<>();
        HttpServlet recording = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                observed.set(new Observed(INITIATOR.find(), AUTHORITY.find()));
            }
        };
        try {
            new MockFilterChain(recording, filters).doFilter(request, new MockHttpServletResponse());
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertThat(observed.get()).as("the chain ran").isNotNull();
        return observed.get();
    }
}
