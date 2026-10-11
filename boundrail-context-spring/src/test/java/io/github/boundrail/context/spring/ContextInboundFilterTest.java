package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Boundary.ATTACHED;
import static io.github.boundrail.context.Boundary.INTERNAL_CALL;
import static io.github.boundrail.context.Boundary.MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextCodec;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.Initiator;
import io.github.boundrail.context.Violation;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(OutputCaptureExtension.class)
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
    record Observed(Optional<String> tenant, Optional<Long> priority, Optional<String> audit, Optional<Initiator> initiator) {
    }

    /** What the servlet at the end of the chain observed, and the violations reported while the request was filtered. */
    record Filtered(Observed observed, List<Violation> violations) {
    }

    /** A string codec that records every text it decodes. */
    static final class RecordingCodec implements ContextCodec<String> {

        final List<String> decoded = new CopyOnWriteArrayList<>();

        @Override
        public String encode(String value) {
            return value;
        }

        @Override
        public String decode(String text) {
            decoded.add(text);
            return text;
        }
    }

    @Test
    @DisplayName("IN-01 ContextInboundFilter is registered before Spring Security's filter chain")
    void contextInboundFilterIsRegisteredBeforeSpringSecuritysFilterChain() {
        runner.run(context -> {
            FilterRegistrationBean<?> registration = inboundRegistration(context);

            assertThat(registration.getOrder()).isEqualTo(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1);
        });
    }

    @Test
    @DisplayName("IN-01 the inbound filter runs before Spring Security at a configured spring.security.filter.order")
    void theInboundFilterRunsBeforeSpringSecurityAtAConfiguredSpringSecurityFilterOrder() {
        runner.withPropertyValues("spring.security.filter.order=-200").run(context -> {
            FilterRegistrationBean<?> registration = inboundRegistration(context);

            assertThat(registration.getOrder()).isEqualTo(-201);
        });
    }

    @Test
    @DisplayName("IN-01 an undeclared checked exception from the chain is rethrown unchanged")
    void anUndeclaredCheckedExceptionFromTheChainIsRethrownUnchanged() {
        runner.withBean(InboundTrustVerifier.class, () -> TRUSTING).run(context -> {
            Filter filter = inboundRegistration(context).getFilter();
            Exception thrown = new Exception("undeclared");
            HttpServlet sneaky = new HttpServlet() {
                @Override
                protected void service(HttpServletRequest req, HttpServletResponse resp) {
                    ContextInboundFilterTest.<RuntimeException>sneakyThrow(thrown);
                }
            };
            MockHttpServletRequest trusted = new MockHttpServletRequest();
            trusted.addHeader("Tenant-Id", "acme");

            Throwable fromBound = catchThrowable(() -> new MockFilterChain(sneaky, filter).doFilter(trusted, new MockHttpServletResponse()));
            Throwable fromUnbound = catchThrowable(() -> new MockFilterChain(sneaky, filter)
                    .doFilter(new MockHttpServletRequest(), new MockHttpServletResponse()));

            assertThat(fromBound).as("with tenant bound").isSameAs(thrown);
            assertThat(fromUnbound).as("with nothing bound").isSameAs(thrown);
        });
    }

    @Test
    @DisplayName("IN-01 no inbound filter or verifier is registered outside a servlet web application")
    void noInboundFilterOrVerifierIsRegisteredOutsideAServletWebApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
                    assertThat(context).doesNotHaveBean(InboundTrustVerifier.class);
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
    @DisplayName("WIRE-02 a trusted Boundrail-Initiator header binds the initiator with that value")
    void aTrustedBoundrailInitiatorHeaderBindsTheInitiatorWithThatValue() {
        runner.withBean(InboundTrustVerifier.class, () -> TRUSTING).run(context -> {
            Optional<Initiator> initiator = observe(inboundRegistration(context).getFilter(), BuiltInKeys.INITIATOR,
                    request -> request.addHeader("Boundrail-Initiator", "user:alice"));

            assertThat(initiator).contains(Initiator.of("user:alice"));
        });
    }

    @Test
    @DisplayName("IN-02 a trusted request binds no authority from headers")
    void aTrustedRequestBindsNoAuthorityFromHeaders() {
        runner.withBean(InboundTrustVerifier.class, () -> TRUSTING).run(context -> {
            Filter filter = inboundRegistration(context).getFilter();
            Consumer<MockHttpServletRequest> headers = request -> {
                request.addHeader("Boundrail-Initiator", "service:edge");
                request.addHeader("Authorization", "Bearer token");
            };

            assertThat(observe(filter, BuiltInKeys.INITIATOR, headers)).contains(Initiator.of("service:edge"));
            assertThat(observe(filter, BuiltInKeys.AUTHORITY, headers)).isEmpty();
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

    @Test
    @DisplayName("IN-02 a repeated policy header on a trusted request leaves the key unbound and reports one INVALID_VALUE")
    void aRepeatedPolicyHeaderOnATrustedRequestLeavesTheKeyUnboundAndReportsOneInvalidValue() {
        RecordingCodec codec = new RecordingCodec();
        ContextPolicy policy = ContextPolicy.builder()
                .key(TENANT, rules -> rules.codec(codec).wireName("Tenant-Id").propagateTo(ATTACHED, INTERNAL_CALL))
                .key(PRIORITY, rules -> rules.codec(Codecs.longValue()).wireName("Priority").propagateTo(ATTACHED, INTERNAL_CALL))
                .build();

        Filtered filtered = filterTrusted(policy, request -> {
            request.addHeader("Tenant-Id", "a");
            request.addHeader("Tenant-Id", "b");
            request.addHeader("Priority", "5");
        });

        assertThat(filtered.observed().tenant()).as("a repeated header leaves the key unbound").isEmpty();
        assertThat(filtered.observed().priority()).as("other keys of the same request are bound").contains(5L);
        assertThat(filtered.violations()).extracting(Violation::kind, Violation::key)
                .containsExactly(tuple(Violation.Kind.INVALID_VALUE, "tenant"));
        assertThat(codec.decoded).as("no value of a repeated header is decoded").isEmpty();
    }

    @Test
    @DisplayName("WIRE-06 repeated headers are counted ignoring case")
    void repeatedHeadersAreCountedIgnoringCase() {
        Filtered filtered = filterTrusted(POLICY, request -> {
            request.addHeader("Tenant-Id", "a");
            request.addHeader("tenant-id", "b");
        });

        assertThat(filtered.observed().tenant()).isEmpty();
        assertThat(filtered.violations()).extracting(Violation::kind, Violation::key)
                .containsExactly(tuple(Violation.Kind.INVALID_VALUE, "tenant"));
    }

    @Test
    @DisplayName("DIAG-08 an inbound violation has boundary INTERNAL_CALL and no destination")
    void anInboundViolationHasBoundaryInternalCallAndNoDestination() {
        Filtered filtered = filterTrusted(POLICY, request -> {
            request.addHeader("Tenant-Id", "a");
            request.addHeader("Tenant-Id", "b");
        });

        assertThat(filtered.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.boundary()).isEqualTo(INTERNAL_CALL);
            assertThat(violation.destination()).isEmpty();
        });
    }

    @Test
    @DisplayName("DIAG-02 a repeated header does not fail the request in fail mode")
    void aRepeatedHeaderDoesNotFailTheRequestInFailMode() {
        Filtered filtered = filterTrusted(POLICY, request -> {
            request.addHeader("Tenant-Id", "a");
            request.addHeader("Tenant-Id", "b");
            request.addHeader("Priority", "5");
        }, "boundrail.context.violations.mode=fail");

        assertThat(filtered.violations()).as("the violation is reported").hasSize(1);
        assertThat(filtered.observed().priority()).as("the chain ran, with the rest of the context bound").contains(5L);
    }

    @Test
    @DisplayName("IN-02 one UNTRUSTED_HEADER violation is reported per policy wire header present")
    void oneUntrustedHeaderViolationIsReportedPerPolicyWireHeaderPresent() {
        Filtered filtered = filterUntrusted(POLICY, request -> {
            request.addHeader("Tenant-Id", "acme");
            request.addHeader("Priority", "5");
            request.addHeader("Boundrail-Initiator", "user:alice");
        });

        assertThat(filtered.violations()).extracting(Violation::kind, Violation::key).containsExactlyInAnyOrder(
                tuple(Violation.Kind.UNTRUSTED_HEADER, "tenant"),
                tuple(Violation.Kind.UNTRUSTED_HEADER, "priority"),
                tuple(Violation.Kind.UNTRUSTED_HEADER, "boundrail.initiator"));
        assertThat(filtered.observed().tenant()).as("nothing is bound").isEmpty();
        assertThat(filtered.observed().priority()).as("nothing is bound").isEmpty();
        assertThat(filtered.observed().initiator()).as("nothing is bound").isEmpty();
    }

    @Test
    @DisplayName("IN-02 a repeated untrusted header is reported once")
    void aRepeatedUntrustedHeaderIsReportedOnce() {
        Filtered sameCase = filterUntrusted(POLICY, request -> {
            request.addHeader("Tenant-Id", "a");
            request.addHeader("Tenant-Id", "b");
        });
        Filtered mixedCase = filterUntrusted(POLICY, request -> {
            request.addHeader("Tenant-Id", "a");
            request.addHeader("tenant-id", "b");
        });

        assertThat(sameCase.violations()).extracting(Violation::kind, Violation::key)
                .containsExactly(tuple(Violation.Kind.UNTRUSTED_HEADER, "tenant"));
        assertThat(mixedCase.violations()).as("names compared ignoring case").extracting(Violation::kind, Violation::key)
                .containsExactly(tuple(Violation.Kind.UNTRUSTED_HEADER, "tenant"));
    }

    @Test
    @DisplayName("DIAG-08 an untrusted header violation has boundary INTERNAL_CALL and no destination")
    void anUntrustedHeaderViolationHasBoundaryInternalCallAndNoDestination() {
        Filtered filtered = filterUntrusted(POLICY, request -> request.addHeader("Tenant-Id", "acme"));

        assertThat(filtered.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.kind()).isEqualTo(Violation.Kind.UNTRUSTED_HEADER);
            assertThat(violation.boundary()).isEqualTo(INTERNAL_CALL);
            assertThat(violation.destination()).isEmpty();
        });
    }

    @Test
    @DisplayName("IN-02 untrusted headers never fail the request")
    void untrustedHeadersNeverFailTheRequest() {
        Filtered filtered = filterUntrusted(POLICY, request -> {
            request.addHeader("Tenant-Id", "acme");
            request.addHeader("Priority", "5");
        }, "boundrail.context.violations.mode=fail");

        assertThat(filtered.violations()).as("the violations are reported").hasSize(2);
        assertThat(filtered.observed()).as("the chain ran").isNotNull();
    }

    @Test
    @DisplayName("IN-02 every wire name of the effective policy's keys is reported, not only those the trusted path binds")
    void everyWireNameOfTheEffectivePolicysKeysIsReportedNotOnlyThoseTheTrustedPathBinds() {
        ContextKey<String> order = ContextKey.of("order", String.class);
        ContextPolicy policy = ContextPolicy.builder()
                .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("Tenant-Id").propagateTo(ATTACHED, INTERNAL_CALL))
                .key(order, rules -> rules.codec(Codecs.string()).wireName("Order-Ref").propagateTo(ATTACHED, MESSAGE))
                .build();

        Filtered deadline = filterUntrusted(policy, request -> request.addHeader("Boundrail-Deadline-Ms", "5000"));
        Filtered messageOnly = filterUntrusted(policy, request -> request.addHeader("Order-Ref", "o-1"));

        assertThat(deadline.violations()).extracting(Violation::kind, Violation::key)
                .containsExactly(tuple(Violation.Kind.UNTRUSTED_HEADER, "boundrail.deadline"));
        assertThat(messageOnly.violations()).extracting(Violation::kind, Violation::key)
                .containsExactly(tuple(Violation.Kind.UNTRUSTED_HEADER, "order"));
    }

    @Test
    @DisplayName("IN-02 Authorization is not a policy wire header")
    void authorizationIsNotAPolicyWireHeader() {
        Filtered filtered = filterUntrusted(POLICY, request -> {
            request.addHeader("Authorization", "Bearer x");
            request.addHeader("Tenant-Id", "acme");
        });

        assertThat(filtered.violations()).extracting(Violation::kind, Violation::key)
                .containsExactly(tuple(Violation.Kind.UNTRUSTED_HEADER, "tenant"));
    }

    @Test
    @DisplayName("IN-02 an empty untrusted policy header is reported")
    void anEmptyUntrustedPolicyHeaderIsReported() {
        Filtered filtered = filterUntrusted(POLICY, request -> request.addHeader("Tenant-Id", ""));

        assertThat(filtered.violations()).extracting(Violation::kind, Violation::key)
                .containsExactly(tuple(Violation.Kind.UNTRUSTED_HEADER, "tenant"));
    }

    @Test
    @DisplayName("IN-02 a single header line with commas is one value and is not split")
    void aSingleHeaderLineWithCommasIsOneValueAndIsNotSplit() {
        Filtered filtered = filterTrusted(POLICY, request -> request.addHeader("Tenant-Id", "a, b"));

        assertThat(filtered.observed().tenant()).as("decoded whole by the string codec").contains("a, b");
        assertThat(filtered.violations()).isEmpty();
    }

    @Test
    @DisplayName("WIRE-04 a trusted value longer than max-value-length is not bound and is reported as OVERSIZED_VALUE")
    void aTrustedValueLongerThanMaxValueLengthIsNotBoundAndIsReportedAsOversizedValue(CapturedOutput output) {
        String oversized = "acme-" + "q".repeat(295);

        Filtered tenant = filterTrusted(POLICY, request -> {
            request.addHeader("Tenant-Id", oversized);
            request.addHeader("Priority", "5");
        }, "boundrail.context.violations.mode=fail");
        Filtered initiator = filterTrusted(POLICY, request -> request.addHeader("Boundrail-Initiator", oversized));
        Filtered deadline = filterTrusted(POLICY, request -> request.addHeader("Boundrail-Deadline-Ms", oversized));

        assertThat(oversized).hasSize(300);
        assertThat(tenant.observed().tenant()).isEmpty();
        assertThat(tenant.observed().priority()).as("the chain ran in fail mode, with the other keys bound").contains(5L);
        assertThat(tenant.violations()).containsExactly(new Violation(Violation.Kind.OVERSIZED_VALUE, "tenant", INTERNAL_CALL,
                Optional.empty(), Thread.currentThread().getName(), Optional.empty(), Optional.empty()));
        assertThat(initiator.observed().initiator()).as("checked before Initiator.of").isEmpty();
        assertThat(initiator.violations()).containsExactly(new Violation(Violation.Kind.OVERSIZED_VALUE, "boundrail.initiator",
                INTERNAL_CALL, Optional.empty(), Thread.currentThread().getName(), Optional.empty(), Optional.empty()));
        assertThat(deadline.violations()).as("the deadline is length-checked too (DL-03)").containsExactly(new Violation(
                Violation.Kind.OVERSIZED_VALUE, "boundrail.deadline", INTERNAL_CALL, Optional.empty(), Thread.currentThread().getName(),
                Optional.empty(), Optional.empty()));
        assertThat(output.getOut()).contains("OVERSIZED_VALUE").contains("key tenant").doesNotContain("acme-")
                .doesNotContain("qqqqqqqqqq");
    }

    @Test
    @DisplayName("WIRE-04 a trusted value of exactly max-value-length is bound")
    void aTrustedValueOfExactlyMaxValueLengthIsBound() {
        String atLimit = "q".repeat(256);

        Filtered filtered = filterTrusted(POLICY, request -> request.addHeader("Tenant-Id", atLimit));

        assertThat(filtered.observed().tenant()).contains(atLimit);
        assertThat(filtered.violations()).isEmpty();
    }

    @Test
    @DisplayName("WIRE-04 the inbound limit is boundrail.context.wire.max-value-length")
    void theInboundLimitIsBoundrailContextWireMaxValueLength() {
        String atLimit = "q".repeat(10);

        Filtered bound = filterTrusted(POLICY, request -> request.addHeader("Tenant-Id", atLimit),
                "boundrail.context.wire.max-value-length=10");
        Filtered oversized = filterTrusted(POLICY, request -> request.addHeader("Tenant-Id", atLimit + "q"),
                "boundrail.context.wire.max-value-length=10");

        assertThat(bound.observed().tenant()).contains(atLimit);
        assertThat(bound.violations()).isEmpty();
        assertThat(oversized.observed().tenant()).isEmpty();
        assertThat(oversized.violations()).extracting(Violation::kind, Violation::key)
                .containsExactly(tuple(Violation.Kind.OVERSIZED_VALUE, "tenant"));
    }

    @Test
    @DisplayName("WIRE-05 a trusted value that fails to decode is not bound and is reported as INVALID_VALUE")
    void aTrustedValueThatFailsToDecodeIsNotBoundAndIsReportedAsInvalidValue() {
        Filtered priority = filterTrusted(POLICY, request -> {
            request.addHeader("Priority", "abc");
            request.addHeader("Tenant-Id", "acme");
        });
        Filtered initiator = filterTrusted(POLICY, request -> request.addHeader("Boundrail-Initiator", ""));

        assertThat(priority.observed().priority()).isEmpty();
        assertThat(priority.observed().tenant()).as("other keys of the same request are bound").contains("acme");
        assertThat(priority.violations()).extracting(Violation::kind, Violation::key)
                .containsExactly(tuple(Violation.Kind.INVALID_VALUE, "priority"));
        assertThat(initiator.observed().initiator()).as("Initiator.of rejects an empty value").isEmpty();
        assertThat(initiator.violations()).extracting(Violation::kind, Violation::key)
                .containsExactly(tuple(Violation.Kind.INVALID_VALUE, "boundrail.initiator"));
    }

    @Test
    @DisplayName("WIRE-05 the request continues after a decode failure")
    void theRequestContinuesAfterADecodeFailure() {
        Filtered filtered = filterTrusted(POLICY, request -> {
            request.addHeader("Priority", "abc");
            request.addHeader("Tenant-Id", "acme");
        }, "boundrail.context.violations.mode=fail");

        assertThat(filtered.violations()).as("the violation is reported").hasSize(1);
        assertThat(filtered.observed().tenant()).as("the chain ran in fail mode, with the other keys bound").contains("acme");
    }

    @Test
    @DisplayName("DIAG-09 a decode failure is reported without the value or the codec's message")
    void aDecodeFailureIsReportedWithoutTheValueOrTheCodecsMessage(CapturedOutput output) {
        String message = catchThrowable(() -> Codecs.longValue().decode("abc")).getMessage();

        Filtered filtered = filterTrusted(POLICY, request -> request.addHeader("Priority", "abc"));

        assertThat(message).as("the codec's message echoes the input").contains("abc");
        assertThat(filtered.violations()).containsExactly(new Violation(Violation.Kind.INVALID_VALUE, "priority", INTERNAL_CALL,
                Optional.empty(), Thread.currentThread().getName(), Optional.empty(), Optional.empty()));
        assertThat(filtered.violations().getFirst().toString()).doesNotContain("abc").doesNotContain(message);
        List<String> entries = output.getOut().lines().filter(line -> line.contains("Context violation")).toList();
        assertThat(entries).singleElement().asString()
                .contains("INVALID_VALUE").contains("key priority").contains("wire name Priority").contains("boundary INTERNAL_CALL")
                .doesNotContain("abc").doesNotContain(message);
        assertThat(output.getOut()).doesNotContain(message);
    }

    @Test
    @DisplayName("DIAG-08 a decode failure has boundary INTERNAL_CALL and no destination")
    void aDecodeFailureHasBoundaryInternalCallAndNoDestination() {
        Filtered filtered = filterTrusted(POLICY, request -> request.addHeader("Priority", "abc"));

        assertThat(filtered.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.kind()).isEqualTo(Violation.Kind.INVALID_VALUE);
            assertThat(violation.boundary()).isEqualTo(INTERNAL_CALL);
            assertThat(violation.destination()).isEmpty();
        });
    }

    @Test
    @DisplayName("WIRE-04 an inbound value both oversized and undecodable is reported once, as OVERSIZED_VALUE, without calling the codec")
    void anInboundValueBothOversizedAndUndecodableIsReportedOnceAsOversizedValueWithoutCallingTheCodec() {
        List<String> decoded = new CopyOnWriteArrayList<>();
        ContextCodec<String> rejecting = new ContextCodec<>() {
            @Override
            public String encode(String value) {
                return value;
            }

            @Override
            public String decode(String text) {
                decoded.add(text);
                throw new IllegalArgumentException("rejected");
            }
        };
        ContextPolicy policy = ContextPolicy.builder()
                .key(TENANT, rules -> rules.codec(rejecting).wireName("Tenant-Id").propagateTo(ATTACHED, INTERNAL_CALL))
                .build();

        Filtered filtered = filterTrusted(policy, request -> request.addHeader("Tenant-Id", "q".repeat(300)));

        assertThat(filtered.observed().tenant()).isEmpty();
        assertThat(filtered.violations()).extracting(Violation::kind, Violation::key)
                .containsExactly(tuple(Violation.Kind.OVERSIZED_VALUE, "tenant"));
        assertThat(decoded).as("the codec is not called for an oversized value").isEmpty();
    }

    @Test
    @DisplayName("WIRE-05 a subclass of IllegalArgumentException from decode is a decode failure")
    void aSubclassOfIllegalArgumentExceptionFromDecodeIsADecodeFailure() {
        ContextCodec<Long> throwing = new ContextCodec<>() {
            @Override
            public String encode(Long value) {
                return value.toString();
            }

            @Override
            public Long decode(String text) {
                throw new NumberFormatException("bad number");
            }
        };
        ContextPolicy policy = ContextPolicy.builder()
                .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("Tenant-Id").propagateTo(ATTACHED, INTERNAL_CALL))
                .key(PRIORITY, rules -> rules.codec(throwing).wireName("Priority").propagateTo(ATTACHED, INTERNAL_CALL))
                .build();

        Filtered filtered = filterTrusted(policy, request -> {
            request.addHeader("Priority", "5");
            request.addHeader("Tenant-Id", "acme");
        });

        assertThat(filtered.observed().priority()).isEmpty();
        assertThat(filtered.observed().tenant()).as("the chain ran").contains("acme");
        assertThat(filtered.violations()).extracting(Violation::kind, Violation::key)
                .containsExactly(tuple(Violation.Kind.INVALID_VALUE, "priority"));
    }

    @Test
    @DisplayName("WIRE-05 any other exception from decode propagates unchanged and no violation is reported")
    void anyOtherExceptionFromDecodePropagatesUnchangedAndNoViolationIsReported() {
        IllegalStateException bug = new IllegalStateException("codec bug");
        ContextCodec<Long> throwing = new ContextCodec<>() {
            @Override
            public String encode(Long value) {
                return value.toString();
            }

            @Override
            public Long decode(String text) {
                throw bug;
            }
        };
        ContextPolicy policy = ContextPolicy.builder()
                .key(PRIORITY, rules -> rules.codec(throwing).wireName("Priority").propagateTo(ATTACHED, INTERNAL_CALL))
                .build();
        List<Violation> violations = new CopyOnWriteArrayList<>();
        AtomicBoolean chainRan = new AtomicBoolean();

        contextRunner(policy)
                .withBean(InboundTrustVerifier.class, () -> TRUSTING)
                .withBean(ViolationReporter.Counter.class, () -> violations::add)
                .run(context -> {
                    MockHttpServletRequest request = new MockHttpServletRequest();
                    request.addHeader("Priority", "5");
                    HttpServlet recording = new HttpServlet() {
                        @Override
                        protected void service(HttpServletRequest req, HttpServletResponse resp) {
                            chainRan.set(true);
                        }
                    };

                    Throwable thrown = catchThrowable(() -> new MockFilterChain(recording, inboundRegistration(context).getFilter())
                            .doFilter(request, new MockHttpServletResponse()));

                    assertThat(thrown).isSameAs(bug);
                });

        assertThat(chainRan.get()).as("the chain did not run").isFalse();
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("WIRE-05 a single comma-containing value that the codec rejects is INVALID_VALUE")
    void aSingleCommaContainingValueThatTheCodecRejectsIsInvalidValue() {
        Filtered filtered = filterTrusted(POLICY, request -> {
            request.addHeader("Priority", "1, 2");
            request.addHeader("Tenant-Id", "acme");
        });

        assertThat(filtered.observed().priority()).isEmpty();
        assertThat(filtered.observed().tenant()).as("the chain ran").contains("acme");
        assertThat(filtered.violations()).extracting(Violation::kind, Violation::key)
                .containsExactly(tuple(Violation.Kind.INVALID_VALUE, "priority"));
    }

    @ParameterizedTest(name = "spring.security.filter.order={0}, lazy initialization: {1}")
    @CsvSource({"abc, false", "abc, true", "-2147483648, false", "-2147483648, true"})
    @DisplayName("IN-01 a spring.security.filter.order that is not an integer or is Integer.MIN_VALUE fails startup")
    void aSpringSecurityFilterOrderThatIsNotAnIntegerOrIsIntegerMinValueFailsStartup(String order, boolean lazy) {
        WebApplicationContextRunner configured = runner.withPropertyValues("spring.security.filter.order=" + order);
        if (lazy) {
            configured = configured.withPropertyValues("spring.main.lazy-initialization=true")
                    .withInitializer(context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));
        }
        configured.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class);
        });
    }

    static FilterRegistrationBean<?> inboundRegistration(AssertableWebApplicationContext context) {
        assertThat(context).hasNotFailed();
        return context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .filter(registration -> registration.getFilter() instanceof ContextInboundFilter)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no FilterRegistrationBean for ContextInboundFilter"));
    }

    /** Throws {@code t}, checked or not, without declaring it. */
    @SuppressWarnings("unchecked")
    static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    /** Sends a request with the given headers through the filter to a servlet that records what it observes. */
    static Observed filter(Filter filter, Consumer<MockHttpServletRequest> headers) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        headers.accept(request);
        AtomicReference<Observed> observed = new AtomicReference<>();
        HttpServlet recording = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                observed.set(new Observed(TENANT.find(), PRIORITY.find(), AUDIT.find(), BuiltInKeys.INITIATOR.find()));
            }
        };
        new MockFilterChain(recording, filter).doFilter(request, new MockHttpServletResponse());
        assertThat(observed.get()).as("the chain ran").isNotNull();
        return observed.get();
    }

    /**
     * Sends a request with the given headers through the filter of a context with the given policy, the given properties and a verifier that
     * trusts every caller, and records every reported violation. Micrometer is hidden so that the recording counter is the only
     * {@code ViolationReporter.Counter} bean.
     */
    private Filtered filterTrusted(ContextPolicy policy, Consumer<MockHttpServletRequest> headers, String... properties) {
        return filterRecording(contextRunner(policy, properties).withBean(InboundTrustVerifier.class, () -> TRUSTING), headers);
    }

    /** As {@link #filterTrusted}, but with the default verifier, which trusts no caller. */
    private Filtered filterUntrusted(ContextPolicy policy, Consumer<MockHttpServletRequest> headers, String... properties) {
        return filterRecording(contextRunner(policy, properties), headers);
    }

    private static WebApplicationContextRunner contextRunner(ContextPolicy policy, String... properties) {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                .withPropertyValues(properties)
                .withBean(ContextPolicy.class, () -> policy);
    }

    private static Filtered filterRecording(WebApplicationContextRunner runner, Consumer<MockHttpServletRequest> headers) {
        List<Violation> violations = new CopyOnWriteArrayList<>();
        AtomicReference<Observed> observed = new AtomicReference<>();
        runner.withBean(ViolationReporter.Counter.class, () -> violations::add)
                .run(context -> observed.set(filter(inboundRegistration(context).getFilter(), headers)));
        return new Filtered(observed.get(), List.copyOf(violations));
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
