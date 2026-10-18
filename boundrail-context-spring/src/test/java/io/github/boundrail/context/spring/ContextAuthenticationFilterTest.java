package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Boundary.ATTACHED;
import static io.github.boundrail.context.Boundary.INTERNAL_CALL;
import static io.github.boundrail.context.BuiltInKeys.AUTHORITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import io.github.boundrail.context.Authority;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
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
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class ContextAuthenticationFilterTest {

    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);
    private static final ContextKey<Long> PRIORITY = ContextKey.of("priority", Long.class);

    private static final ContextPolicy POLICY = ContextPolicy.builder()
            .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("Tenant-Id").propagateTo(ATTACHED, INTERNAL_CALL))
            .key(PRIORITY, rules -> rules.codec(Codecs.longValue()).wireName("Priority").propagateTo(ATTACHED, INTERNAL_CALL))
            .build();

    private static final Authentication ALICE = UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of());

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
            .withBean(ContextPolicy.class, () -> POLICY);

    /** A contributor that runs the given action; subclasses carry an {@code @Order}. */
    abstract static class ActionContributor implements ContextContributor {

        private final BiConsumer<Authentication, ContextContributions> action;

        ActionContributor(BiConsumer<Authentication, ContextContributions> action) {
            this.action = action;
        }

        @Override
        public void contribute(Authentication authentication, ContextContributions contributions) {
            action.accept(authentication, contributions);
        }
    }

    @Order(1)
    static final class FirstContributor extends ActionContributor {
        FirstContributor(BiConsumer<Authentication, ContextContributions> action) {
            super(action);
        }
    }

    @Order(2)
    static final class SecondContributor extends ActionContributor {
        SecondContributor(BiConsumer<Authentication, ContextContributions> action) {
            super(action);
        }
    }

    /** What the servlet at the end of the chain observed. */
    record Observed(Optional<String> tenant, Optional<Long> priority) {
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("IN-04 ContextAuthenticationFilter is registered after Spring Security's filter chain when Spring Security is present")
    void contextAuthenticationFilterIsRegisteredAfterSpringSecuritysFilterChain() {
        runner.run(context -> assertThat(authenticationRegistration(context).getOrder())
                .isEqualTo(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1));
    }

    @Test
    @DisplayName("IN-04 the authentication filter runs after Spring Security at a configured spring.security.filter.order")
    void theAuthenticationFilterRunsAfterSpringSecurityAtAConfiguredSpringSecurityFilterOrder() {
        runner.withPropertyValues("spring.security.filter.order=5")
                .run(context -> assertThat(authenticationRegistration(context).getOrder()).isEqualTo(6));
    }

    @ParameterizedTest(name = "lazy initialization: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("IN-04 spring.security.filter.order of Integer.MAX_VALUE fails startup")
    void springSecurityFilterOrderOfIntegerMaxValueFailsStartup(boolean lazy) {
        WebApplicationContextRunner configured = runner.withPropertyValues("spring.security.filter.order=2147483647");
        if (lazy) {
            configured = configured.withPropertyValues("spring.main.lazy-initialization=true")
                    .withInitializer(context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));
        }
        configured.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    @DisplayName("IN-04 spring.security.filter.order of Integer.MAX_VALUE starts without Spring Security")
    void springSecurityFilterOrderOfIntegerMaxValueStartsWithoutSpringSecurity() {
        runner.withPropertyValues("spring.security.filter.order=2147483647")
                .withClassLoader(new FilteredClassLoader(SecurityContextHolder.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(ContextInboundFilterTest.inboundRegistration(context).getOrder()).isEqualTo(Integer.MAX_VALUE - 1);
                });
    }

    @Test
    @DisplayName("IN-04 an undeclared checked exception from the chain is rethrown unchanged")
    void anUndeclaredCheckedExceptionFromTheChainIsRethrownUnchanged() {
        runner.withBean(ContextContributor.class, () -> (authentication, contributions) -> contributions.put(TENANT, "acme"))
                .run(context -> {
                    Filter filter = authenticationRegistration(context).getFilter();
                    Exception thrown = new Exception("undeclared");
                    AtomicReference<Optional<String>> tenant = new AtomicReference<>();
                    HttpServlet sneaky = new HttpServlet() {
                        @Override
                        protected void service(HttpServletRequest req, HttpServletResponse resp) {
                            tenant.set(TENANT.find());
                            ContextInboundFilterTest.<RuntimeException>sneakyThrow(thrown);
                        }
                    };
                    SecurityContextHolder.getContext().setAuthentication(ALICE);

                    Throwable caught = catchThrowable(() -> new MockFilterChain(sneaky, filter)
                            .doFilter(new MockHttpServletRequest(), new MockHttpServletResponse()));

                    assertThat(tenant.get()).as("the chain ran with tenant bound").contains("acme");
                    assertThat(caught).isSameAs(thrown);
                });
    }

    @Test
    @DisplayName("IN-04 it is not registered without Spring Security")
    void itIsNotRegisteredWithoutSpringSecurity() {
        runner.withClassLoader(new FilteredClassLoader(SecurityContextHolder.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(ContextInboundFilterTest.inboundRegistration(context)).as("the inbound filter, as a control").isNotNull();
            assertThat(context.getBeansOfType(FilterRegistrationBean.class).values())
                    .noneMatch(registration -> registration.getFilter() instanceof ContextAuthenticationFilter);
        });
    }

    @Test
    @DisplayName("IN-04 for an authenticated request every ContextContributor bean is invoked and the rest of the chain runs with the contributed values")
    void forAnAuthenticatedRequestEveryContributorIsInvokedAndTheChainRunsWithTheContributedValues() {
        List<Authentication> received = new CopyOnWriteArrayList<>();
        runner.withBean("tenantContributor", ContextContributor.class, () -> (authentication, contributions) -> {
                    received.add(authentication);
                    contributions.put(TENANT, "acme");
                })
                .withBean("priorityContributor", ContextContributor.class, () -> (authentication, contributions) -> {
                    received.add(authentication);
                    contributions.put(PRIORITY, 5L);
                })
                .run(context -> {
                    Observed observed = filter(context, ALICE);

                    assertThat(observed).isEqualTo(new Observed(Optional.of("acme"), Optional.of(5L)));
                    assertThat(received).containsExactly(ALICE, ALICE);
                });
    }

    @Test
    @DisplayName("IN-04 the contributed values are bound only for the rest of the chain")
    void theContributedValuesAreBoundOnlyForTheRestOfTheChain() {
        runner.withBean(ContextContributor.class, () -> (authentication, contributions) -> contributions.put(TENANT, "acme"))
                .run(context -> {
                    Observed observed = filter(context, ALICE);

                    assertThat(observed.tenant()).as("in the chain").contains("acme");
                    assertThat(TENANT.isBound()).as("after the filter").isFalse();
                });
    }

    @Test
    @DisplayName("IN-04 contributors are not invoked without an authentication, or when isAuthenticated() is false")
    void contributorsAreNotInvokedWithoutAnAuthenticationOrWhenNotAuthenticated() {
        List<Authentication> received = new CopyOnWriteArrayList<>();
        runner.withBean(ContextContributor.class, () -> (authentication, contributions) -> {
                    received.add(authentication);
                    contributions.put(TENANT, "acme");
                })
                .run(context -> {
                    Observed none = filter(context, null);
                    Observed unauthenticated = filter(context, UsernamePasswordAuthenticationToken.unauthenticated("bob", null));
                    assertThat(received).as("no contributor invoked").isEmpty();

                    Observed authenticated = filter(context, ALICE);

                    assertThat(none.tenant()).as("without an authentication").isEmpty();
                    assertThat(unauthenticated.tenant()).as("with isAuthenticated() false").isEmpty();
                    assertThat(authenticated.tenant()).as("authenticated, as a control").contains("acme");
                    assertThat(received).containsExactly(ALICE);
                });
    }

    @Test
    @DisplayName("IN-04 an anonymous authentication is not authenticated")
    void anAnonymousAuthenticationIsNotAuthenticated() {
        Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        assertThat(anonymous.isAuthenticated()).as("precondition: it reports isAuthenticated()").isTrue();
        List<Authentication> received = new CopyOnWriteArrayList<>();
        runner.withBean(ContextContributor.class, () -> (authentication, contributions) -> {
                    received.add(authentication);
                    contributions.put(TENANT, "acme");
                })
                .run(context -> {
                    Observed observed = filter(context, anonymous);

                    assertThat(observed.tenant()).isEmpty();
                    assertThat(received).isEmpty();
                });
    }

    @Test
    @DisplayName("IN-05 a contributed value does not replace a key already bound")
    void aContributedValueDoesNotReplaceAKeyAlreadyBound() {
        runner.withBean(InboundTrustVerifier.class, () -> request -> InboundTrustVerifier.Trust.TRUSTED_INTERNAL)
                .withBean(ContextContributor.class, () -> (authentication, contributions) -> contributions.put(TENANT, "contributed"))
                .run(context -> {
                    Filter inbound = ContextInboundFilterTest.inboundRegistration(context).getFilter();
                    Filter authentication = authenticationRegistration(context).getFilter();

                    Observed withHeader = filter(ALICE, request -> request.addHeader("Tenant-Id", "acme"), inbound, authentication);
                    Observed withoutHeader = filter(ALICE, request -> { }, inbound, authentication);

                    assertThat(withHeader.tenant()).as("bound by the trusted header").contains("acme");
                    assertThat(withoutHeader.tenant()).as("not bound, as a control").contains("contributed");
                });
    }

    @Test
    @DisplayName("IN-05 the first contribution for a key wins")
    void theFirstContributionForAKeyWins() {
        runner.withBean(FirstContributor.class,
                        () -> new FirstContributor((authentication, contributions) -> contributions.put(TENANT, "first")))
                .withBean(SecondContributor.class,
                        () -> new SecondContributor((authentication, contributions) -> contributions.put(TENANT, "second")))
                .run(context -> assertThat(filter(context, ALICE).tenant()).as("from two contributors").contains("first"));

        runner.withBean(ContextContributor.class, () -> (authentication, contributions) -> {
                    contributions.put(TENANT, "first");
                    contributions.put(TENANT, "second");
                })
                .run(context -> assertThat(filter(context, ALICE).tenant()).as("from one contributor").contains("first"));
    }

    @Test
    @DisplayName("IN-05 contributors are invoked in bean order")
    void contributorsAreInvokedInBeanOrder() {
        List<String> invoked = new CopyOnWriteArrayList<>();
        runner.withBean(SecondContributor.class, () -> new SecondContributor((authentication, contributions) -> invoked.add("second")))
                .withBean(FirstContributor.class, () -> new FirstContributor((authentication, contributions) -> invoked.add("first")))
                .run(context -> {
                    filter(context, ALICE);

                    assertThat(invoked).containsExactly("first", "second");
                });
    }

    @Test
    @DisplayName("CORE-13 ContextContributions.put rejects null and values of the wrong type")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void putRejectsNullAndValuesOfTheWrongType() {
        List<Throwable> thrown = new ArrayList<>();
        runner.withBean(ContextContributor.class, () -> (authentication, contributions) -> {
                    ContextKey raw = TENANT;
                    thrown.add(catchThrowable(() -> contributions.put(null, "acme")));
                    thrown.add(catchThrowable(() -> contributions.put(TENANT, null)));
                    thrown.add(catchThrowable(() -> contributions.put(raw, 5L)));
                    contributions.put(TENANT, "acme");
                    thrown.add(catchThrowable(() -> contributions.put(TENANT, null)));
                    thrown.add(catchThrowable(() -> contributions.put(raw, 5L)));
                })
                .run(context -> {
                    Observed observed = filter(context, ALICE);

                    assertThat(thrown).hasSize(5);
                    assertThat(thrown.get(0)).as("null key").isInstanceOf(NullPointerException.class);
                    assertThat(thrown.get(1)).as("null value").isInstanceOf(NullPointerException.class);
                    assertThat(thrown.get(2)).as("wrong type").isInstanceOf(ClassCastException.class);
                    assertThat(thrown.get(3)).as("null value for a key already contributed").isInstanceOf(NullPointerException.class);
                    assertThat(thrown.get(4)).as("wrong type for a key already contributed").isInstanceOf(ClassCastException.class);
                    assertThat(observed.tenant()).contains("acme");
                });
    }

    @Test
    @DisplayName("ID-02 ContextContributions.put throws IllegalArgumentException for AUTHORITY, after its null checks")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void putThrowsIllegalArgumentExceptionForAuthority() {
        JwtAuthenticationToken aliceJwt = new JwtAuthenticationToken(
                Jwt.withTokenValue("alice-jwt").header("alg", "none").subject("alice").build(), List.of());
        List<Throwable> thrown = new ArrayList<>();
        AtomicReference<Optional<Authority>> authority = new AtomicReference<>();
        runner.withBean(ContextContributor.class, () -> (authentication, contributions) -> {
                    ContextKey raw = AUTHORITY;
                    thrown.add(catchThrowable(() -> contributions.put(AUTHORITY, Authority.of("mallory", "forged"))));
                    thrown.add(catchThrowable(() -> contributions.put(AUTHORITY, null)));
                    thrown.add(catchThrowable(() -> contributions.put(raw, "text")));
                })
                .run(context -> {
                    Filter filter = authenticationRegistration(context).getFilter();
                    HttpServlet recording = new HttpServlet() {
                        @Override
                        protected void service(HttpServletRequest req, HttpServletResponse resp) {
                            authority.set(AUTHORITY.find());
                        }
                    };
                    SecurityContextHolder.getContext().setAuthentication(aliceJwt);

                    new MockFilterChain(recording, filter).doFilter(new MockHttpServletRequest(), new MockHttpServletResponse());

                    assertThat(thrown).hasSize(3);
                    assertThat(thrown.get(0)).as("an authority").isInstanceOf(IllegalArgumentException.class);
                    assertThat(thrown.get(1)).as("null").isInstanceOf(NullPointerException.class);
                    assertThat(thrown.get(2)).as("a value of the wrong type through a raw key").isInstanceOf(IllegalArgumentException.class);
                    assertThat(authority.get()).as("the request's authority").hasValueSatisfying(bound -> {
                        assertThat(bound.subject()).isEqualTo("alice");
                        assertThat(bound.credential()).isEqualTo("alice-jwt");
                    });
                });
    }

    @Test
    @DisplayName("ID-02 a contributor's IllegalArgumentException propagates from the filter")
    void aContributorsIllegalArgumentExceptionPropagatesFromTheFilter() {
        runner.withBean(ContextContributor.class,
                        () -> (authentication, contributions) -> contributions.put(AUTHORITY, Authority.of("mallory", "forged")))
                .run(context -> {
                    Filter filter = authenticationRegistration(context).getFilter();
                    List<String> ran = new CopyOnWriteArrayList<>();
                    HttpServlet recording = new HttpServlet() {
                        @Override
                        protected void service(HttpServletRequest req, HttpServletResponse resp) {
                            ran.add("chain");
                        }
                    };
                    SecurityContextHolder.getContext().setAuthentication(ALICE);

                    Throwable caught = catchThrowable(() -> new MockFilterChain(recording, filter)
                            .doFilter(new MockHttpServletRequest(), new MockHttpServletResponse()));

                    assertThat(caught).isInstanceOf(IllegalArgumentException.class);
                    assertThat(ran).as("the chain ran").isEmpty();
                });
    }

    @Test
    @DisplayName("IN-07 invalid(key) reports one INVALID_VALUE")
    void invalidReportsOneInvalidValue() {
        Recorded recorded = filterRecording((authentication, contributions) -> contributions.invalid(TENANT));

        assertThat(recorded.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.kind()).isEqualTo(Violation.Kind.INVALID_VALUE);
            assertThat(violation.key()).isEqualTo("tenant");
            assertThat(violation.boundary()).isEqualTo(INTERNAL_CALL);
            assertThat(violation.destination()).isEmpty();
        });
    }

    @Test
    @DisplayName("IN-07 invalid(key) binds nothing")
    void invalidBindsNothing() {
        Recorded recorded = filterRecording((authentication, contributions) -> contributions.invalid(TENANT));

        assertThat(recorded.observed()).isEqualTo(new Observed(Optional.empty(), Optional.empty()));
    }

    @Test
    @DisplayName("IN-07 invalid(key) does not affect contributions for the same key")
    void invalidDoesNotAffectContributionsForTheSameKey() {
        Recorded putThenInvalid = filterRecording((authentication, contributions) -> {
            contributions.put(TENANT, "acme");
            contributions.invalid(TENANT);
        });
        Recorded invalidThenPut = filterRecording((authentication, contributions) -> {
            contributions.invalid(TENANT);
            contributions.put(TENANT, "acme");
        });

        assertThat(putThenInvalid.observed().tenant()).as("put, then invalid").contains("acme");
        assertThat(invalidThenPut.observed().tenant()).as("invalid, then put").contains("acme");
        assertThat(putThenInvalid.violations()).as("put, then invalid").extracting(Violation::kind, Violation::key)
                .containsExactly(tuple(Violation.Kind.INVALID_VALUE, "tenant"));
        assertThat(invalidThenPut.violations()).as("invalid, then put").extracting(Violation::kind, Violation::key)
                .containsExactly(tuple(Violation.Kind.INVALID_VALUE, "tenant"));
    }

    @Test
    @DisplayName("CORE-13 ContextContributions.invalid(null) throws NullPointerException")
    void invalidNullThrowsNullPointerException() {
        List<Throwable> thrown = new ArrayList<>();
        Recorded recorded = filterRecording((authentication, contributions) -> thrown.add(catchThrowable(() -> contributions.invalid(null))));

        assertThat(thrown).singleElement().isInstanceOf(NullPointerException.class);
        assertThat(recorded.violations()).isEmpty();
    }

    /** What the servlet at the end of the chain observed, and the violations reported while the request was filtered. */
    record Recorded(Observed observed, List<Violation> violations) {
    }

    /**
     * Sends an authenticated request through the authentication filter of a context in {@code fail} mode with the given contributor, and
     * records every reported violation. Micrometer is hidden so that the recording counter is the only {@code ViolationReporter.Counter} bean.
     */
    private Recorded filterRecording(ContextContributor contributor) {
        List<Violation> violations = new CopyOnWriteArrayList<>();
        AtomicReference<Observed> observed = new AtomicReference<>();
        runner.withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                .withPropertyValues("boundrail.context.violations.mode=fail")
                .withBean(ViolationReporter.Counter.class, () -> violations::add)
                .withBean(ContextContributor.class, () -> contributor)
                .run(context -> observed.set(filter(context, ALICE)));
        return new Recorded(observed.get(), List.copyOf(violations));
    }

    private static FilterRegistrationBean<?> authenticationRegistration(AssertableWebApplicationContext context) {
        assertThat(context).hasNotFailed();
        return context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .filter(registration -> registration.getFilter() instanceof ContextAuthenticationFilter)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no FilterRegistrationBean for ContextAuthenticationFilter"));
    }

    /** Sends a request with the given authentication, or none, through the context's authentication filter. */
    private static Observed filter(AssertableWebApplicationContext context, Authentication authentication) throws Exception {
        return filter(authentication, request -> { }, authenticationRegistration(context).getFilter());
    }

    /**
     * Sends a request with the given headers through the filters to a servlet that records what it observes, with the given authentication,
     * or none, in the {@code SecurityContextHolder}.
     */
    private static Observed filter(Authentication authentication, Consumer<MockHttpServletRequest> headers, Filter... filters)
            throws Exception {
        SecurityContextHolder.clearContext();
        if (authentication != null) {
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        MockHttpServletRequest request = new MockHttpServletRequest();
        headers.accept(request);
        AtomicReference<Observed> observed = new AtomicReference<>();
        HttpServlet recording = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                observed.set(new Observed(TENANT.find(), PRIORITY.find()));
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
