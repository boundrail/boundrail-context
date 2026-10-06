package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Boundary.ATTACHED;
import static io.github.boundrail.context.Boundary.INTERNAL_CALL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.ContextRuntime;
import io.github.boundrail.context.ContextRuntime.CaptureSites;
import io.github.boundrail.context.ContextSnapshot;
import io.github.boundrail.context.ContextViolationException;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.spring.support.RecordingServer;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;

/** Every adapter fills the observed evidence of a violation through the reporter (DIAG-04). Capture sites are on for each test. */
class ViolationEvidenceTest {

    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);
    private static final ContextKey<String> AUDIT = ContextKey.of("audit", String.class);

    private static final ContextPolicy POLICY = ContextPolicy.builder()
            .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("Tenant-Id").required().propagateTo(ATTACHED, INTERNAL_CALL))
            .key(AUDIT, rules -> rules.codec(Codecs.string()).wireName("Audit").propagateTo(ATTACHED))
            .build();

    /** Matches a capture site in a frame of this test class. */
    private static final String SITE_IN_THIS_CLASS = "io\\.github\\.boundrail\\.context\\.spring\\.ViolationEvidenceTest\\.[\\w$]+\\("
            + "ViolationEvidenceTest\\.java:\\d+\\)";

    private CaptureSites savedMode;
    private double savedRate;
    private final List<Violation> violations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void captureSitesOn() {
        savedMode = ContextRuntime.captureSites();
        savedRate = ContextRuntime.sampleRate();
        ContextRuntime.captureSites(CaptureSites.ON, savedRate);
    }

    @AfterEach
    void restoreCaptureSites() {
        ContextRuntime.captureSites(savedMode, savedRate);
        SecurityContextHolder.clearContext();
    }

    /** Calls an internal client through the auto-configured RestClient.Builder, in fail mode, inside the snapshot, and returns what it threw. */
    private Throwable callInternalClient(ContextSnapshot snapshot) {
        Throwable[] thrown = new Throwable[1];
        try (RecordingServer server = RecordingServer.start("ok")) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class, RestClientAutoConfiguration.class))
                    .withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                    .withPropertyValues("boundrail.context.violations.mode=fail")
                    .withBean(ContextPolicy.class, () -> POLICY)
                    .withBean(ViolationReporter.Counter.class, () -> violations::add)
                    .run(context -> {
                        RestClient client = context.getBean(RestClient.Builder.class).baseUrl(server.uri())
                                .apply(BoundrailClients.internal("orders")).build();
                        thrown[0] = catchThrowable(() -> snapshot.run(() -> client.get().uri("/").retrieve().toBodilessEntity()));
                    });
        }
        return thrown[0];
    }

    private static Violation violationOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(ContextViolationException.class);
        return ((ContextViolationException) thrown).violation();
    }

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
            .withClassLoader(new FilteredClassLoader(MeterRegistry.class))
            .withBean(ContextPolicy.class, () -> POLICY)
            .withBean(ViolationReporter.Counter.class, () -> violations::add);

    private static Filter filterOfType(AssertableWebApplicationContext context, Class<? extends Filter> type) {
        assertThat(context).hasNotFailed();
        return context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .map(FilterRegistrationBean::getFilter)
                .filter(type::isInstance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no FilterRegistrationBean for " + type.getSimpleName()));
    }

    private static void doFilter(Filter filter, MockHttpServletRequest request) throws Exception {
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
            }
        };
        new MockFilterChain(servlet, filter).doFilter(request, new MockHttpServletResponse());
    }

    @Test
    @DisplayName("DIAG-04 a violation from the client interceptor carries the thread and the capture site of the current snapshot")
    void aViolationFromTheClientInterceptorCarriesTheThreadAndTheCaptureSite() {
        List<Violation> thrownViolations = new ArrayList<>();
        Context.without(TENANT).run(() -> thrownViolations.add(violationOf(callInternalClient(Context.capture()))));

        assertThat(thrownViolations).singleElement().satisfies(violation -> {
            assertThat(violation.kind()).isEqualTo(Violation.Kind.MISSING_REQUIRED);
            assertThat(violation.thread()).isEqualTo(Thread.currentThread().getName());
            assertThat(violation.captureSite()).hasValueSatisfying(site -> assertThat(site).matches(SITE_IN_THIS_CLASS));
        });
    }

    @Test
    @DisplayName("DIAG-04 violations from the inbound filter and from ContextContributions.invalid carry the capture site of their scope")
    void violationsFromTheInboundFilterAndFromInvalidCarryTheCaptureSiteOfTheirScope() {
        ContextSnapshot scope = Context.with(AUDIT, "kept").call(Context::capture);
        assertThat(scope.captureSite()).as("the test's scope has a site").isPresent();

        webRunner.withBean(InboundTrustVerifier.class, () -> request -> InboundTrustVerifier.Trust.UNTRUSTED).run(context -> {
            Filter inbound = filterOfType(context, ContextInboundFilter.class);
            MockHttpServletRequest untrusted = new MockHttpServletRequest();
            untrusted.addHeader("Tenant-Id", "acme");

            scope.call(() -> {
                doFilter(inbound, untrusted);
                return null;
            });
        });
        List<Violation> fromInbound = List.copyOf(violations);
        violations.clear();

        webRunner.withBean(ContextContributor.class, () -> (authentication, contributions) -> contributions.invalid(TENANT)).run(context -> {
            Filter authentication = filterOfType(context, ContextAuthenticationFilter.class);
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));

            scope.call(() -> {
                doFilter(authentication, new MockHttpServletRequest());
                return null;
            });
        });
        List<Violation> fromInvalid = List.copyOf(violations);

        assertThat(fromInbound).as("inbound filter").singleElement().satisfies(violation -> {
            assertThat(violation.kind()).isEqualTo(Violation.Kind.UNTRUSTED_HEADER);
            assertThat(violation.captureSite()).isEqualTo(scope.captureSite());
        });
        assertThat(fromInvalid).as("ContextContributions.invalid").singleElement().satisfies(violation -> {
            assertThat(violation.kind()).isEqualTo(Violation.Kind.INVALID_VALUE);
            assertThat(violation.captureSite()).isEqualTo(scope.captureSite());
        });
    }

    @Test
    @DisplayName("DIAG-04 a violation in a scope without a capture site has none")
    void aViolationInAScopeWithoutACaptureSiteHasNone() {
        ContextRuntime.captureSites(CaptureSites.OFF, savedRate);
        ContextSnapshot withoutSite = Context.without(TENANT).call(Context::capture);
        ContextRuntime.captureSites(CaptureSites.ON, savedRate);
        ContextSnapshot withSite = Context.without(TENANT).call(Context::capture);

        Violation inScopeWithoutSite = violationOf(callInternalClient(withoutSite));
        Violation inScopeWithSite = violationOf(callInternalClient(withSite));

        assertThat(inScopeWithoutSite.captureSite()).isEmpty();
        assertThat(inScopeWithSite.captureSite()).as("the same call in a scope with a site").isEqualTo(withSite.captureSite()).isPresent();
    }
}
