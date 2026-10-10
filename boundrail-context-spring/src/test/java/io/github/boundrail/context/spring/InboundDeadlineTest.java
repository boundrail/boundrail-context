package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Boundary.ATTACHED;
import static io.github.boundrail.context.Boundary.INTERNAL_CALL;
import static io.github.boundrail.context.spring.ContextInboundFilterTest.inboundRegistration;
import static io.github.boundrail.context.spring.ContextInboundFilterTest.observe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.ContextSnapshot;
import io.github.boundrail.context.Deadline;
import io.github.boundrail.context.Violation;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/** The deadline {@code ContextInboundFilter} binds from a trusted {@code Boundrail-Deadline-Ms} or from {@code default-inbound} (DL-03). */
class InboundDeadlineTest {

    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);

    private static final ContextPolicy POLICY = ContextPolicy.builder()
            .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("Tenant-Id").propagateTo(ATTACHED, INTERNAL_CALL))
            .build();

    private static final InboundTrustVerifier TRUSTING = request -> InboundTrustVerifier.Trust.TRUSTED_INTERNAL;

    private final List<Violation> violations = new CopyOnWriteArrayList<>();

    /** Micrometer is hidden, so the recording counter is the only {@code ViolationReporter.Counter} bean. */
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
            .withClassLoader(new FilteredClassLoader(MeterRegistry.class))
            .withBean(ContextPolicy.class, () -> POLICY)
            .withBean(ViolationReporter.Counter.class, () -> violations::add);

    private static Consumer<MockHttpServletRequest> deadlineHeader(String value) {
        return request -> request.addHeader("Boundrail-Deadline-Ms", value);
    }

    /** Sends one request through the filter of a context with the given verifier and properties; returns the deadline the chain found. */
    private Optional<Deadline> deadline(boolean trusted, Consumer<MockHttpServletRequest> headers, String... properties) {
        AtomicReference<Optional<Deadline>> found = new AtomicReference<>();
        WebApplicationContextRunner configured = runner.withPropertyValues(properties);
        if (trusted) {
            configured = configured.withBean(InboundTrustVerifier.class, () -> TRUSTING);
        }
        configured.run(context -> found.set(observe(inboundRegistration(context).getFilter(), BuiltInKeys.DEADLINE, headers)));
        return found.get();
    }

    @Test
    @DisplayName("DL-03 a trusted Boundrail-Deadline-Ms binds Deadline.after(value)")
    void aTrustedBoundrailDeadlineMsBindsDeadlineAfterValue() {
        Optional<Deadline> deadline = deadline(true, deadlineHeader("2000"));

        assertThat(deadline).hasValueSatisfying(bound -> assertThat(bound.remaining())
                .isGreaterThan(Duration.ofMillis(1500))
                .isLessThanOrEqualTo(Duration.ofMillis(2000)));
    }

    @Test
    @DisplayName("DL-03 a trusted value is capped by max-inbound, also when it is too large for a long")
    void aTrustedValueIsCappedByMaxInbound() {
        for (String value : new String[] {"600000", "99999999999999999999"}) {
            assertThat(deadline(true, deadlineHeader(value))).as("%s with the default max-inbound", value)
                    .hasValueSatisfying(bound -> assertThat(bound.remaining()).isLessThanOrEqualTo(Duration.ofSeconds(30))
                            .isGreaterThan(Duration.ofSeconds(25)));
            assertThat(deadline(true, deadlineHeader(value), "boundrail.context.deadline.max-inbound=1s")).as("%s with max-inbound=1s", value)
                    .hasValueSatisfying(bound -> assertThat(bound.remaining()).isLessThanOrEqualTo(Duration.ofSeconds(1))
                            .isGreaterThan(Duration.ofMillis(500)));
        }
    }

    @Test
    @DisplayName("DL-03 with no header, default-inbound is bound if set, and may exceed max-inbound; otherwise no deadline is bound")
    void withNoHeaderDefaultInboundIsBoundIfSetOtherwiseNoDeadline() {
        Optional<Deadline> withDefault = deadline(true, request -> { }, "boundrail.context.deadline.default-inbound=60s",
                "boundrail.context.deadline.max-inbound=30s");
        Optional<Deadline> withoutDefault = deadline(true, request -> { });

        assertThat(withDefault).hasValueSatisfying(bound -> assertThat(bound.remaining())
                .isGreaterThan(Duration.ofSeconds(30))
                .isLessThanOrEqualTo(Duration.ofSeconds(60)));
        assertThat(withoutDefault).as("without a default no deadline is bound").isEmpty();
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("DL-03 an untrusted Boundrail-Deadline-Ms is ignored and reported once as UNTRUSTED_HEADER, and default-inbound applies if set")
    void anUntrustedBoundrailDeadlineMsIsIgnoredAndReportedAndDefaultInboundApplies() {
        Optional<Deadline> withDefault = deadline(false, deadlineHeader("5"), "boundrail.context.deadline.default-inbound=500ms");
        List<Violation> reportedWithDefault = List.copyOf(violations);
        violations.clear();
        Optional<Deadline> withoutDefault = deadline(false, deadlineHeader("5"));

        assertThat(withDefault).as("default-inbound applies").hasValueSatisfying(bound -> assertThat(bound.remaining())
                .isGreaterThan(Duration.ofMillis(5))
                .isLessThanOrEqualTo(Duration.ofMillis(500)));
        assertThat(withoutDefault).as("without a default no deadline is bound").isEmpty();
        assertThat(reportedWithDefault).extracting(Violation::kind, Violation::key)
                .containsExactly(tuple(Violation.Kind.UNTRUSTED_HEADER, "boundrail.deadline"));
        assertThat(violations).extracting(Violation::kind, Violation::key)
                .containsExactly(tuple(Violation.Kind.UNTRUSTED_HEADER, "boundrail.deadline"));
    }

    /** The deadline the chain found for a trusted request with the given headers, and the violations reported for that request. */
    private record Result(Optional<Deadline> deadline, List<Violation> violations) {
    }

    private Result trusted(Consumer<MockHttpServletRequest> headers, String... properties) {
        violations.clear();
        Optional<Deadline> deadline = deadline(true, headers, properties);
        return new Result(deadline, List.copyOf(violations));
    }

    /** Asserts that the 500ms default is bound. */
    private static void assertDefaultBound(Optional<Deadline> deadline) {
        assertThat(deadline).as("default-inbound applies").hasValueSatisfying(bound -> assertThat(bound.remaining())
                .isGreaterThan(Duration.ofMillis(400))
                .isLessThanOrEqualTo(Duration.ofMillis(500)));
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"abc", "-1", "+5", "1.5", "", " 5", "5 ", "\uFF11\uFF12"})
    @DisplayName("DL-03 a trusted value that is not one or more ASCII digits binds nothing, is reported once as INVALID_VALUE, and default-inbound applies")
    void aTrustedValueThatIsNotAsciiDigitsIsReportedAsInvalidValue(String value) {
        Result withDefault = trusted(deadlineHeader(value), "boundrail.context.deadline.default-inbound=500ms");
        Result withoutDefault = trusted(deadlineHeader(value));

        assertDefaultBound(withDefault.deadline());
        assertThat(withoutDefault.deadline()).as("without a default nothing is bound").isEmpty();
        for (Result result : List.of(withDefault, withoutDefault)) {
            assertThat(result.violations()).extracting(Violation::kind, Violation::key)
                    .containsExactly(tuple(Violation.Kind.INVALID_VALUE, "boundrail.deadline"));
            if (!value.isBlank()) {
                assertThat(result.violations().getFirst().toString()).as("DIAG-09: without the value").doesNotContain(value.strip());
            }
        }
    }

    @Test
    @DisplayName("DL-03 a trusted header on more than one line binds nothing, is reported once as INVALID_VALUE, and default-inbound applies")
    void aTrustedHeaderOnMoreThanOneLineIsReportedOnceAsInvalidValue() {
        Consumer<MockHttpServletRequest> twoLines = request -> {
            request.addHeader("Boundrail-Deadline-Ms", "5000");
            request.addHeader("Boundrail-Deadline-Ms", "6000");
        };
        Consumer<MockHttpServletRequest> oneLineTooLong = request -> {
            request.addHeader("Boundrail-Deadline-Ms", "5000");
            request.addHeader("Boundrail-Deadline-Ms", "1".repeat(300));
        };

        Result repeated = trusted(twoLines, "boundrail.context.deadline.default-inbound=500ms");
        Result repeatedWithoutDefault = trusted(twoLines);
        Result repeatedTooLong = trusted(oneLineTooLong, "boundrail.context.deadline.default-inbound=500ms");

        assertDefaultBound(repeated.deadline());
        assertThat(repeatedWithoutDefault.deadline()).as("without a default nothing is bound").isEmpty();
        assertDefaultBound(repeatedTooLong.deadline());
        for (Result result : List.of(repeated, repeatedWithoutDefault, repeatedTooLong)) {
            assertThat(result.violations()).as("one INVALID_VALUE and no OVERSIZED_VALUE (G6)").extracting(Violation::kind, Violation::key)
                    .containsExactly(tuple(Violation.Kind.INVALID_VALUE, "boundrail.deadline"));
        }
    }

    @Test
    @DisplayName("DL-03 a trusted value longer than wire.max-value-length is reported as OVERSIZED_VALUE, and default-inbound applies")
    void aTrustedValueLongerThanMaxValueLengthIsReportedAsOversizedValue() {
        Result oversized = trusted(deadlineHeader("5000"), "boundrail.context.wire.max-value-length=3",
                "boundrail.context.deadline.default-inbound=500ms");
        Result fits = trusted(deadlineHeader("500"), "boundrail.context.wire.max-value-length=3");

        assertDefaultBound(oversized.deadline());
        assertThat(oversized.violations()).extracting(Violation::kind, Violation::key)
                .containsExactly(tuple(Violation.Kind.OVERSIZED_VALUE, "boundrail.deadline"));
        assertThat(fits.deadline()).as("a value of max-value-length is bound").hasValueSatisfying(bound -> assertThat(bound.remaining())
                .isGreaterThan(Duration.ofMillis(400))
                .isLessThanOrEqualTo(Duration.ofMillis(500)));
        assertThat(fits.violations()).isEmpty();
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

    /** Records, in {@code postHandle}, the deadline bound for the handler; for a {@code DeferredResult}, on the {@code ASYNC} dispatch. */
    static final class RecordingInterceptor implements HandlerInterceptor {

        final List<Optional<Deadline>> seen = new CopyOnWriteArrayList<>();

        @Override
        public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
            seen.add(BuiltInKeys.DEADLINE.find());
        }
    }

    @Test
    @DisplayName("DL-03 the deadline is bound in the same scope as the IN-02 keys, so the stored snapshot holds it")
    void theDeadlineIsBoundInTheSameScopeAsTheIn02Keys() {
        DeferredController controller = new DeferredController();
        RecordingInterceptor interceptor = new RecordingInterceptor();
        runner.withBean(InboundTrustVerifier.class, () -> TRUSTING).run(context -> {
            FilterRegistrationBean<?> registration = inboundRegistration(context);
            Filter filter = registration.getFilter();
            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(interceptor)
                    .addFilter(filter, "inbound", Map.of(), registration.determineDispatcherTypes(), "/*")
                    .build();

            MvcResult started = mockMvc.perform(get("/deferred").header("Tenant-Id", "acme").header("Boundrail-Deadline-Ms", "60000"))
                    .andReturn();
            ContextSnapshot stored = (ContextSnapshot) started.getRequest().getAttribute(ContextInboundFilter.SNAPSHOT_ATTRIBUTE);
            Thread completing = new Thread(() -> controller.pending.get().setResult("done"));
            completing.start();
            completing.join();
            mockMvc.perform(asyncDispatch(started));

            assertThat(stored.find(TENANT)).contains("acme");
            assertThat(stored.find(BuiltInKeys.DEADLINE)).isPresent();
            // postHandle runs only on the ASYNC dispatch: the initial one starts async processing instead.
            assertThat(interceptor.seen).as("the ASYNC dispatch sees the stored deadline")
                    .containsExactly(Optional.of(stored.find(BuiltInKeys.DEADLINE).orElseThrow()));
            assertThat(interceptor.seen.getFirst().orElseThrow()).isSameAs(stored.find(BuiltInKeys.DEADLINE).orElseThrow());
        });
    }

    /** What a trusted request with the given headers produced: the response, and whether the servlet at the end of the chain ran. */
    private record Answered(MockHttpServletResponse response, boolean chainRan) {
    }

    private Answered answer(Consumer<MockHttpServletRequest> headers, String... properties) {
        AtomicReference<Answered> answered = new AtomicReference<>();
        runner.withPropertyValues(properties).withBean(InboundTrustVerifier.class, () -> TRUSTING).run(context -> {
            MockHttpServletRequest request = new MockHttpServletRequest();
            headers.accept(request);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean chainRan = new AtomicBoolean();
            HttpServlet servlet = new HttpServlet() {
                @Override
                protected void service(HttpServletRequest req, HttpServletResponse resp) {
                    chainRan.set(true);
                }
            };
            new MockFilterChain(servlet, inboundRegistration(context).getFilter()).doFilter(request, response);
            answered.set(new Answered(response, chainRan.get()));
        });
        return answered.get();
    }

    @Test
    @DisplayName("DL-04 a request whose deadline is already expired gets expired-status with an empty body, without sendError, and the chain is not invoked")
    void aRequestWhoseDeadlineIsAlreadyExpiredGetsExpiredStatus() {
        Answered answered = answer(deadlineHeader("0"));

        assertThat(answered.response().getStatus()).isEqualTo(504);
        assertThat(answered.response().getContentLength()).isZero();
        assertThat(answered.response().getContentAsByteArray()).isEmpty();
        assertThat(answered.response().getErrorMessage()).as("no sendError").isNull();
        assertThat(answered.response().isCommitted()).as("no sendError, which would commit the response").isFalse();
        assertThat(answered.chainRan()).as("the chain is not invoked").isFalse();
    }

    @Test
    @DisplayName("DL-04 expired-status is configurable")
    void expiredStatusIsConfigurable() {
        Answered answered = answer(deadlineHeader("0"), "boundrail.context.deadline.expired-status=503");

        assertThat(answered.response().getStatus()).isEqualTo(503);
        assertThat(answered.chainRan()).isFalse();
    }

    @Test
    @DisplayName("DL-04 the check is made once, on the initial dispatch")
    void theCheckIsMadeOnceOnTheInitialDispatch() {
        DeferredController controller = new DeferredController();
        runner.withBean(InboundTrustVerifier.class, () -> TRUSTING).run(context -> {
            FilterRegistrationBean<?> registration = inboundRegistration(context);
            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                    .addFilter(registration.getFilter(), "inbound", Map.of(), registration.determineDispatcherTypes(), "/*")
                    .build();

            MvcResult expired = mockMvc.perform(get("/deferred").header("Boundrail-Deadline-Ms", "0")).andReturn();
            MvcResult started = mockMvc.perform(get("/deferred").header("Boundrail-Deadline-Ms", "50")).andReturn();
            // Read now: the ASYNC dispatch below resets the mock request's async state.
            boolean reachedHandler = started.getRequest().isAsyncStarted();
            Deadline deadline = ((ContextSnapshot) started.getRequest().getAttribute(ContextInboundFilter.SNAPSHOT_ATTRIBUTE))
                    .find(BuiltInKeys.DEADLINE).orElseThrow();
            while (!deadline.isExpired()) {
                Thread.sleep(5);
            }
            controller.pending.get().setResult("done");
            MvcResult dispatched = mockMvc.perform(asyncDispatch(started)).andReturn();

            assertThat(expired.getResponse().getStatus()).as("the initial dispatch is checked").isEqualTo(504);
            assertThat(reachedHandler).as("the 50ms request reached the handler").isTrue();
            assertThat(dispatched.getResponse().getStatus()).as("the ASYNC dispatch is not checked again").isEqualTo(200);
            assertThat(dispatched.getResponse().getContentAsString()).isEqualTo("done");
        });
    }
}
