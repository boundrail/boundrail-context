package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Boundary.ATTACHED;
import static io.github.boundrail.context.Boundary.INTERNAL_CALL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ContextInboundFilterTest {

    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);
    private static final ContextKey<Long> PRIORITY = ContextKey.of("priority", Long.class);
    private static final ContextKey<String> AUDIT = ContextKey.of("audit", String.class);

    /** Two keys that propagate to {@code INTERNAL_CALL} and one, {@code audit}, that propagates only in-process. */
    private static final ContextPolicy POLICY = ContextPolicy.builder()
            .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("Tenant-Id").propagateTo(ATTACHED, INTERNAL_CALL))
            .key(PRIORITY, rules -> rules.codec(Codecs.longValue()).wireName("Priority").propagateTo(ATTACHED, INTERNAL_CALL))
            .key(AUDIT, rules -> rules.codec(Codecs.string()).wireName("Audit").propagateTo(ATTACHED))
            .build();

    private static final InboundTrustVerifier TRUSTING = request -> InboundTrustVerifier.Trust.TRUSTED_INTERNAL;

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
            .withBean(ContextPolicy.class, () -> POLICY);

    /** What the servlet at the end of the chain observed. */
    record Observed(Optional<String> tenant, Optional<Long> priority, Optional<String> audit) {
    }

    @Test
    @DisplayName("IN-01 ContextInboundFilter is registered before Spring Security's filter chain")
    void contextInboundFilterIsRegisteredBeforeSpringSecuritysFilterChain() {
        runner.run(context -> {
            FilterRegistrationBean<?> registration = inboundRegistration(context);

            assertThat(registration.getOrder()).isLessThan(SecurityFilterProperties.DEFAULT_FILTER_ORDER);
        });
    }

    @Test
    @DisplayName("IN-01 the rest of the chain runs inside the scope and the previous snapshot is restored afterwards")
    void theRestOfTheChainRunsInsideTheScopeAndThePreviousSnapshotIsRestoredAfterwards() {
        runner.withBean(InboundTrustVerifier.class, () -> TRUSTING).run(context -> {
            Filter filter = inboundRegistration(context).getFilter();

            Context.with(TENANT, "outer").with(AUDIT, "kept").call(() -> {
                Observed observed = filter(filter, request -> request.addHeader("Tenant-Id", "acme"));

                assertThat(observed.tenant()).as("bound by the filter for the rest of the chain").contains("acme");
                assertThat(observed.audit()).as("the scope is nested in the caller's").contains("kept");
                assertThat(TENANT.find()).as("restored after the chain").contains("outer");
                return null;
            });
            assertThat(TENANT.isBound()).isFalse();

            ServletException thrown = new ServletException("boom");
            AtomicReference<Optional<String>> inside = new AtomicReference<>();
            Context.with(TENANT, "outer").call(() -> {
                MockHttpServletRequest request = new MockHttpServletRequest();
                request.addHeader("Tenant-Id", "acme");
                HttpServlet throwing = new HttpServlet() {
                    @Override
                    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException {
                        inside.set(TENANT.find());
                        throw thrown;
                    }
                };

                Throwable caught = catchThrowable(() -> new MockFilterChain(throwing, filter).doFilter(request, new MockHttpServletResponse()));

                assertThat(caught).as("rethrown unchanged").isSameAs(thrown);
                assertThat(inside.get()).contains("acme");
                assertThat(TENANT.find()).as("restored after the chain threw").contains("outer");
                return null;
            });
        });
    }

    @Test
    @DisplayName("IN-02 a trusted request binds every key that propagates to INTERNAL_CALL and whose header is present")
    void aTrustedRequestBindsEveryKeyThatPropagatesToInternalCallAndWhoseHeaderIsPresent() {
        runner.withBean(InboundTrustVerifier.class, () -> TRUSTING).run(context -> {
            Filter filter = inboundRegistration(context).getFilter();

            Observed both = filter(filter, request -> {
                request.addHeader("Tenant-Id", "acme");
                request.addHeader("Priority", "5");
            });
            Observed tenantOnly = filter(filter, request -> request.addHeader("Tenant-Id", "acme"));

            assertThat(both.tenant()).contains("acme");
            assertThat(both.priority()).as("decoded with the key's codec").contains(5L);
            assertThat(tenantOnly.tenant()).contains("acme");
            assertThat(tenantOnly.priority()).as("no header, so not bound").isEmpty();
        });
    }

    @Test
    @DisplayName("IN-02 keys not propagating to INTERNAL_CALL are not bound from headers")
    void keysNotPropagatingToInternalCallAreNotBoundFromHeaders() {
        runner.withBean(InboundTrustVerifier.class, () -> TRUSTING).run(context -> {
            Observed observed = filter(inboundRegistration(context).getFilter(), request -> {
                request.addHeader("Tenant-Id", "acme");
                request.addHeader("Audit", "forged");
            });

            assertThat(observed.tenant()).contains("acme");
            assertThat(observed.audit()).isEmpty();
        });
    }

    @Test
    @DisplayName("WIRE-01 inbound values are read from the header named by the wire name")
    void inboundValuesAreReadFromTheHeaderNamedByTheWireName() {
        runner.withBean(InboundTrustVerifier.class, () -> TRUSTING).run(context -> {
            Filter filter = inboundRegistration(context).getFilter();

            Observed byKeyName = filter(filter, request -> {
                request.addHeader("tenant", "acme");
                request.addHeader("priority", "5");
            });
            Observed byWireName = filter(filter, request -> request.addHeader("Tenant-Id", "acme"));

            assertThat(byKeyName.tenant()).as("the key name is not the header name").isEmpty();
            assertThat(byWireName.tenant()).contains("acme");
        });
    }

    @Test
    @DisplayName("WIRE-06 inbound header names match ignoring case")
    void inboundHeaderNamesMatchIgnoringCase() {
        runner.withBean(InboundTrustVerifier.class, () -> TRUSTING).run(context -> {
            Observed observed = filter(inboundRegistration(context).getFilter(), request -> {
                request.addHeader("tenant-id", "acme");
                request.addHeader("PRIORITY", "5");
            });

            assertThat(observed.tenant()).contains("acme");
            assertThat(observed.priority()).contains(5L);
        });
    }

    @Test
    @DisplayName("IN-03 an InboundTrustVerifier bean replaces the built-in verifier")
    void anInboundTrustVerifierBeanReplacesTheBuiltInVerifier() {
        runner.withBean(InboundTrustVerifier.class, () -> TRUSTING).run(context -> {
            assertThat(context).getBean(InboundTrustVerifier.class).isSameAs(TRUSTING);

            Observed observed = filter(inboundRegistration(context).getFilter(), request -> request.addHeader("Tenant-Id", "acme"));

            assertThat(observed.tenant()).contains("acme");
        });
    }

    static FilterRegistrationBean<?> inboundRegistration(AssertableWebApplicationContext context) {
        assertThat(context).hasNotFailed();
        return context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .filter(registration -> registration.getFilter() instanceof ContextInboundFilter)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no FilterRegistrationBean for ContextInboundFilter"));
    }

    /** Sends a request with the given headers through the filter to a servlet that records what it observes. */
    static Observed filter(Filter filter, Consumer<MockHttpServletRequest> headers) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        headers.accept(request);
        AtomicReference<Observed> observed = new AtomicReference<>();
        HttpServlet recording = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                observed.set(new Observed(TENANT.find(), PRIORITY.find(), AUDIT.find()));
            }
        };
        new MockFilterChain(recording, filter).doFilter(request, new MockHttpServletResponse());
        assertThat(observed.get()).as("the chain ran").isNotNull();
        return observed.get();
    }

    /** Sends a request with the given headers through the filter and returns what the servlet at the end of the chain finds for the key. */
    static <T> Optional<T> observe(Filter filter, ContextKey<T> key, Consumer<MockHttpServletRequest> headers) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        headers.accept(request);
        AtomicReference<Optional<T>> observed = new AtomicReference<>();
        HttpServlet recording = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                observed.set(key.find());
            }
        };
        new MockFilterChain(recording, filter).doFilter(request, new MockHttpServletResponse());
        assertThat(observed.get()).as("the chain ran").isNotNull();
        return observed.get();
    }
}
