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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
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
}
