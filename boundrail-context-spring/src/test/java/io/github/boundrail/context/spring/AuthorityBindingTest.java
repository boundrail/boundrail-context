package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Boundary.ATTACHED;
import static io.github.boundrail.context.Boundary.DETACHED;
import static io.github.boundrail.context.Boundary.INTERNAL_CALL;
import static io.github.boundrail.context.BuiltInKeys.AUTHORITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.boundrail.context.Authority;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.ContextSnapshot;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/** {@code ContextAuthenticationFilter} binds {@code AUTHORITY} from a verified OAuth2 token authentication (ID-02, ID-06, ID-10). */
class AuthorityBindingTest {

    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);

    private static final ContextPolicy POLICY = ContextPolicy.builder()
            .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("Tenant-Id").loggable().propagateTo(ATTACHED, INTERNAL_CALL))
            .build();

    private static final String JWT_VALUE = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJhbGljZSJ9.jwt-signature";
    private static final String OPAQUE_VALUE = "opaque-access-token-value";
    private static final Instant ISSUED = Instant.parse("2026-10-13T12:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-10-13T13:00:00Z");

    private static final JwtAuthenticationToken ALICE_JWT = new JwtAuthenticationToken(Jwt.withTokenValue(JWT_VALUE).header("alg", "none")
            .subject("alice").issuedAt(ISSUED).expiresAt(EXPIRES).build(), List.of());

    private static final ContextContributor TENANT_CONTRIBUTOR = (authentication, contributions) -> contributions.put(TENANT, "acme");

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
            .withBean(ContextPolicy.class, () -> POLICY);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        MDC.remove("tenant");
    }

    /** What the servlet at the end of the chain observed. */
    record Observed(Optional<Authority> authority, Optional<String> tenant) {
    }

    @Test
    @DisplayName("ID-02 a JwtAuthenticationToken binds AUTHORITY with its name, token value and expiry")
    void aJwtAuthenticationTokenBindsAuthority() {
        runner.run(context -> {
            Authority authority = filter(context, ALICE_JWT).authority().orElseThrow(() -> new AssertionError("no AUTHORITY bound"));

            assertThat(authority.subject()).isEqualTo("alice");
            assertThat(authority.credential()).isEqualTo(JWT_VALUE);
            assertThat(authority.expiresAt()).contains(Instant.parse("2026-10-13T13:00:00Z"));
        });
    }

    @Test
    @DisplayName("ID-02 a BearerTokenAuthentication binds AUTHORITY, and a token without an expiry gives an authority without one")
    void aBearerTokenAuthenticationBindsAuthority() {
        DefaultOAuth2AuthenticatedPrincipal principal = new DefaultOAuth2AuthenticatedPrincipal("alice", Map.of("sub", "alice"), List.of());
        Authentication withExpiry = new BearerTokenAuthentication(principal,
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, OPAQUE_VALUE, ISSUED, EXPIRES), List.of());
        Authentication withoutExpiry = new BearerTokenAuthentication(principal,
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, OPAQUE_VALUE, null, null), List.of());

        runner.run(context -> {
            Authority expiring = filter(context, withExpiry).authority().orElseThrow(() -> new AssertionError("no AUTHORITY bound"));
            Authority unbounded = filter(context, withoutExpiry).authority().orElseThrow(() -> new AssertionError("no AUTHORITY bound"));

            assertThat(expiring.subject()).isEqualTo("alice");
            assertThat(expiring.credential()).isEqualTo(OPAQUE_VALUE);
            assertThat(expiring.expiresAt()).contains(Instant.parse("2026-10-13T13:00:00Z"));
            assertThat(unbounded.subject()).isEqualTo("alice");
            assertThat(unbounded.credential()).isEqualTo(OPAQUE_VALUE);
            assertThat(unbounded.expiresAt()).isEmpty();
        });
    }

    @Test
    @DisplayName("ID-02 an authentication that is not an OAuth2 token, or a request that is not authenticated, binds no AUTHORITY")
    void anAuthenticationThatIsNotAnOAuth2TokenBindsNoAuthority() {
        Authentication password = UsernamePasswordAuthenticationToken.authenticated("alice", "password", List.of());
        Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        runner.run(context -> {
            assertThat(filter(context, password).authority()).as("username and password").isEmpty();
            assertThat(filter(context, anonymous).authority()).as("anonymous").isEmpty();
            assertThat(filter(context, null).authority()).as("no authentication").isEmpty();
            assertThat(filter(context, ALICE_JWT).authority()).as("a JWT, as the control").isPresent();
        });
    }

    @Test
    @DisplayName("ID-02 AUTHORITY is bound from the authentication even when it is already bound")
    void authorityIsBoundEvenWhenAlreadyBound() {
        Authority outer = Authority.of("svc", "x");

        runner.run(context -> {
            Observed observed = Context.with(AUTHORITY, outer).call(() -> filter(context, ALICE_JWT));

            assertThat(observed.authority()).hasValueSatisfying(authority -> {
                assertThat(authority).isNotSameAs(outer);
                assertThat(authority.subject()).isEqualTo("alice");
                assertThat(authority.credential()).isEqualTo(JWT_VALUE);
            });
        });
    }

    /** Returns a {@code DeferredResult} the test completes. */
    @RestController
    static final class DeferredController {

        final AtomicReference<DeferredResult<String>> pending = new AtomicReference<>();

        @GetMapping("/deferred")
        DeferredResult<String> deferred() {
            DeferredResult<String> result = new DeferredResult<>();
            pending.set(result);
            return result;
        }
    }

    /** Records, in {@code postHandle} of the {@code ASYNC} dispatch, the authority bound for the handler. */
    static final class AsyncAuthorityInterceptor implements HandlerInterceptor {

        final AtomicReference<Optional<Authority>> async = new AtomicReference<>();

        @Override
        public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
            if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC) {
                async.set(AUTHORITY.find());
            }
        }
    }

    @Test
    @DisplayName("ID-02 AUTHORITY is bound in the nested scope of the contributions, so the stored snapshot and an ASYNC dispatch hold it")
    void authorityIsBoundInTheNestedScopeOfTheContributions() {
        DeferredController controller = new DeferredController();
        AsyncAuthorityInterceptor interceptor = new AsyncAuthorityInterceptor();

        runner.withBean(ContextContributor.class, () -> TENANT_CONTRIBUTOR).run(context -> {
            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(interceptor)
                    .addFilter(registration(context, ContextInboundFilter.class).getFilter(), "inbound", Map.of(),
                            registration(context, ContextInboundFilter.class).determineDispatcherTypes(), "/*")
                    .addFilter(registration(context, ContextAuthenticationFilter.class).getFilter(), "authentication", Map.of(),
                            registration(context, ContextAuthenticationFilter.class).determineDispatcherTypes(), "/*")
                    .build();
            SecurityContextHolder.getContext().setAuthentication(ALICE_JWT);

            MvcResult started = mockMvc.perform(get("/deferred")).andReturn();
            Thread completing = new Thread(() -> controller.pending.get().setResult("done"));
            completing.start();
            completing.join();
            mockMvc.perform(asyncDispatch(started));

            ContextSnapshot stored = (ContextSnapshot) started.getRequest().getAttribute(ContextInboundFilter.SNAPSHOT_ATTRIBUTE);
            assertThat(stored.find(TENANT)).as("stored tenant").contains("acme");
            Authority storedAuthority = stored.find(AUTHORITY).orElseThrow(() -> new AssertionError("no AUTHORITY in the stored snapshot"));
            assertThat(storedAuthority.credential()).isEqualTo(JWT_VALUE);
            assertThat(interceptor.async.get()).as("in the ASYNC dispatch").containsSame(storedAuthority);
            assertThat(AUTHORITY.isBound()).as("after the filter").isFalse();
        });
    }

    @Test
    @DisplayName("ID-06 the bound authority is not in the MDC, nor in the snapshot filtered for DETACHED")
    void theBoundAuthorityIsNotInTheMdcNorInTheDetachedSnapshot() {
        AtomicReference<Map<String, String>> mdc = new AtomicReference<>();
        AtomicReference<ContextSnapshot> captured = new AtomicReference<>();

        runner.withBean(ContextContributor.class, () -> TENANT_CONTRIBUTOR).run(context -> {
            filter(ALICE_JWT, () -> {
                mdc.set(MDC.getCopyOfContextMap() == null ? Map.of() : Map.copyOf(MDC.getCopyOfContextMap()));
                captured.set(Context.capture());
            }, registration(context, ContextAuthenticationFilter.class).getFilter());
            ContextSnapshot detached = context.getBean(EffectivePolicy.class).policy().filter(captured.get(), DETACHED);

            assertThat(captured.get().find(AUTHORITY)).as("the unfiltered capture").isPresent();
            assertThat(mdc.get()).as("MDC").containsEntry("tenant", "acme").doesNotContainKey("boundrail.authority");
            assertThat(mdc.get().values()).as("MDC values").noneMatch(value -> value.contains(JWT_VALUE));
            assertThat(detached.find(AUTHORITY)).as("filtered for DETACHED").isEmpty();
        });
    }

    @Test
    @DisplayName("ID-02 without Spring Security's OAuth2 resource server no AUTHORITY is bound and contributors still run")
    void withoutTheResourceServerNoAuthorityIsBound() {
        runner.withClassLoader(new FilteredClassLoader(AbstractOAuth2TokenAuthenticationToken.class))
                .withBean(ContextContributor.class, () -> TENANT_CONTRIBUTOR)
                .run(context -> {
                    Observed observed = filter(context, UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));

                    assertThat(context.getBeansOfType(AuthorityExtraction.class)).isEmpty();
                    assertThat(observed.tenant()).contains("acme");
                    assertThat(observed.authority()).isEmpty();
                });
    }

    private static FilterRegistrationBean<?> registration(AssertableWebApplicationContext context, Class<?> filterType) {
        assertThat(context).hasNotFailed();
        return context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .filter(registration -> filterType.isInstance(registration.getFilter()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no FilterRegistrationBean for " + filterType.getSimpleName()));
    }

    /** Sends a request with the given authentication, or none, through the context's authentication filter, recording what the chain sees. */
    private static Observed filter(AssertableWebApplicationContext context, Authentication authentication) throws Exception {
        AtomicReference<Observed> observed = new AtomicReference<>();
        filter(authentication, () -> observed.set(new Observed(AUTHORITY.find(), TENANT.find())),
                registration(context, ContextAuthenticationFilter.class).getFilter());
        assertThat(observed.get()).as("the chain ran").isNotNull();
        return observed.get();
    }

    /** Runs the filters to a servlet that runs {@code inChain}, with the given authentication, or none, in the {@code SecurityContextHolder}. */
    private static void filter(Authentication authentication, Runnable inChain, Filter... filters) throws Exception {
        SecurityContextHolder.clearContext();
        if (authentication != null) {
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                inChain.run();
            }
        };
        try {
            new MockFilterChain(servlet, filters).doFilter(new MockHttpServletRequest(), new MockHttpServletResponse());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
