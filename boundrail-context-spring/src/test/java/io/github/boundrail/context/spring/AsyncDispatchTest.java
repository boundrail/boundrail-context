package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Boundary.ATTACHED;
import static io.github.boundrail.context.Boundary.INTERNAL_CALL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.Violation;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/** {@code ASYNC} dispatches of a Spring MVC async request re-enter the snapshot stored on the request's initial dispatch (IN-09). */
class AsyncDispatchTest {

    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);
    private static final ContextKey<Long> PRIORITY = ContextKey.of("priority", Long.class);

    private static final ContextPolicy POLICY = ContextPolicy.builder()
            .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("Tenant-Id").propagateTo(ATTACHED, INTERNAL_CALL))
            .build();

    /** Trusts the first request it sees, and no later one. */
    static final class FirstCallTrusted implements InboundTrustVerifier {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Trust verify(HttpServletRequest request) {
            return calls.incrementAndGet() == 1 ? Trust.TRUSTED_INTERNAL : Trust.UNTRUSTED;
        }
    }

    /** Contributes {@code priority=7} and counts its invocations. */
    static final class CountingContributor implements ContextContributor {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void contribute(org.springframework.security.core.Authentication authentication, ContextContributions contributions) {
            calls.incrementAndGet();
            contributions.put(PRIORITY, 7L);
        }
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

    /** Records, in {@code postHandle}, the dispatch type and the tenant and priority bound for the handler. */
    static final class RecordingInterceptor implements HandlerInterceptor {

        final List<String> seen = new CopyOnWriteArrayList<>();

        @Override
        public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
            seen.add(request.getDispatcherType() + " tenant=" + TENANT.find().orElse("-") + " priority=" + PRIORITY.find().map(String::valueOf)
                    .orElse("-"));
        }
    }

    private final FirstCallTrusted verifier = new FirstCallTrusted();
    private final CountingContributor contributor = new CountingContributor();
    private final List<Violation> violations = new CopyOnWriteArrayList<>();
    private final DeferredController controller = new DeferredController();
    private final RecordingInterceptor interceptor = new RecordingInterceptor();

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
            // Without Micrometer, so the test's counter is the only one.
            .withClassLoader(new FilteredClassLoader(MeterRegistry.class))
            .withBean(ContextPolicy.class, () -> POLICY)
            .withBean(InboundTrustVerifier.class, () -> verifier)
            .withBean(ContextContributor.class, () -> contributor)
            .withBean(ViolationReporter.Counter.class, () -> violations::add);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static FilterRegistrationBean<?> registration(AssertableWebApplicationContext context, Class<?> filterType) {
        assertThat(context).hasNotFailed();
        return context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .filter(registration -> filterType.isInstance(registration.getFilter()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no FilterRegistrationBean for " + filterType.getSimpleName()));
    }

    /** A MockMvc with the given library filters, in this order, each for the dispatch types of its registration. */
    private MockMvc mockMvc(AssertableWebApplicationContext context, Class<?>... filterTypes) {
        var builder = MockMvcBuilders.standaloneSetup(controller).addInterceptors(interceptor);
        for (Class<?> filterType : filterTypes) {
            FilterRegistrationBean<?> registration = registration(context, filterType);
            builder.addFilter(registration.getFilter(), filterType.getSimpleName(), Map.of(), registration.determineDispatcherTypes(), "/*");
        }
        return builder.build();
    }

    /** Sends a request with {@code Tenant-Id: acme}, completes its {@code DeferredResult} from a thread with no context, and dispatches it again. */
    private void requestThenAsyncDispatch(MockMvc mockMvc) throws Exception {
        MvcResult started = mockMvc.perform(get("/deferred").header("Tenant-Id", "acme")).andReturn();
        Thread completing = new Thread(() -> controller.pending.get().setResult("done"));
        completing.start();
        completing.join();
        mockMvc.perform(asyncDispatch(started));
    }

    @Test
    @DisplayName("IN-09 the inbound filter is registered for REQUEST and ASYNC dispatches")
    void theInboundFilterIsRegisteredForRequestAndAsyncDispatches() {
        runner.run(context -> assertThat(registration(context, ContextInboundFilter.class).determineDispatcherTypes())
                .isEqualTo(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC)));
    }

    @Test
    @DisplayName("IN-09 an ASYNC dispatch runs the rest of the chain in the stored snapshot without asking the verifier, decoding or reporting")
    void anAsyncDispatchRunsTheRestOfTheChainInTheStoredSnapshot() {
        runner.run(context -> {
            requestThenAsyncDispatch(mockMvc(context, ContextInboundFilter.class));

            assertThat(interceptor.seen).containsExactly("ASYNC tenant=acme priority=-");
            assertThat(verifier.calls).as("verifier calls").hasValue(1);
            assertThat(violations).as("violations reported").isEmpty();
        });
    }

    @Test
    @DisplayName("IN-09 the stored snapshot is the innermost scope's")
    void theStoredSnapshotIsTheInnermostScopes() {
        runner.run(context -> {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));

            requestThenAsyncDispatch(mockMvc(context, ContextInboundFilter.class, ContextAuthenticationFilter.class));

            assertThat(interceptor.seen).containsExactly("ASYNC tenant=acme priority=7");
            assertThat(contributor.calls).as("contributor invocations").hasValue(1);
        });
    }

    @Test
    @DisplayName("IN-09 ContextAuthenticationFilter does not run on ASYNC dispatches")
    void contextAuthenticationFilterDoesNotRunOnAsyncDispatches() {
        runner.run(context -> {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));

            requestThenAsyncDispatch(mockMvc(context, ContextAuthenticationFilter.class));

            assertThat(contributor.calls).as("contributor invocations").hasValue(1);
        });
    }

    @Test
    @DisplayName("IN-09 an ASYNC dispatch with no stored snapshot runs in the current scope")
    void anAsyncDispatchWithNoStoredSnapshotRunsInTheCurrentScope() {
        runner.run(context -> {
            // The inbound filter runs on ASYNC dispatches only, so the initial dispatch stores no snapshot.
            FilterRegistrationBean<?> inbound = registration(context, ContextInboundFilter.class);
            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(interceptor)
                    .addFilter(inbound.getFilter(), "inbound", Map.of(), EnumSet.of(DispatcherType.ASYNC), "/*").build();
            MvcResult started = mockMvc.perform(get("/deferred")).andReturn();
            controller.pending.get().setResult("done");

            Context.with(TENANT, "current").call(() -> mockMvc.perform(asyncDispatch(started)));

            assertThat(interceptor.seen).containsExactly("ASYNC tenant=current priority=-");
        });
    }
}
