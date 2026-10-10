package io.github.boundrail.context.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.boundrail.context.BoundExecutors;
import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.Deadline;
import io.github.boundrail.context.DeadlineExceededException;
import io.github.boundrail.context.spring.support.RecordingServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** The counter {@code boundrail.context.deadline.exceeded}, tagged by stage, in the context's unique or primary registry (DL-09). */
class DeadlineMetricsTest {

    private static final String COUNTER = "boundrail.context.deadline.exceeded";
    private static final long TIMEOUT_MILLIS = 5_000;

    /** Handlers that let a deadline expire at different stages. */
    @RestController
    static final class Handlers {

        private final RestClient.Builder clients;
        private final String partner;

        Handlers(RestClient.Builder clients, String partner) {
            this.clients = clients;
            this.partner = partner;
        }

        /** Throws {@code Deadline.check()}'s exception, stage {@code APPLICATION}. */
        @GetMapping("/application")
        String application() {
            Deadline.after(Duration.ZERO).check();
            return "unreachable";
        }

        /** Calls out with an expired deadline, so {@code BEFORE_SEND} escapes the handler. */
        @GetMapping("/before-send")
        String beforeSend() {
            RestClient client = clients.baseUrl(partner).build();
            return Context.with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ZERO)).call(() -> client.get().uri("/").retrieve().body(String.class));
        }

        @GetMapping("/callable")
        Callable<String> callable() {
            return () -> "called";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class Mvc {
    }

    /** The handlers, with the auto-configured {@code RestClient.Builder}, which carries the library's interceptor. */
    @Configuration(proxyBeanMethods = false)
    static class HandlersConfiguration {

        @Bean
        Handlers handlers(RestClient.Builder clients, org.springframework.core.env.Environment environment) {
            return new Handlers(clients, environment.getRequiredProperty("test.partner"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OneRegistry {

        @Bean
        SimpleMeterRegistry registry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PrimaryAndOther {

        @Bean
        @Primary
        SimpleMeterRegistry primaryRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        SimpleMeterRegistry otherRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoWithoutPrimary {

        @Bean
        SimpleMeterRegistry firstRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        SimpleMeterRegistry secondRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    private static WebApplicationContextRunner runner(RecordingServer partner) {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class, HttpClientAutoConfiguration.class,
                        ImperativeHttpClientAutoConfiguration.class, RestClientAutoConfiguration.class))
                .withPropertyValues("spring.http.clients.imperative.factory=jdk")
                .withUserConfiguration(Mvc.class)
                .withBean(InboundTrustVerifier.class, () -> request -> InboundTrustVerifier.Trust.TRUSTED_INTERNAL)
                .withPropertyValues("test.partner=" + partner.uri())
                .withUserConfiguration(HandlersConfiguration.class);
    }

    /** The counts of the deadline counter in the registry, by stage tag. */
    private static Map<String, Double> counts(MeterRegistry registry) {
        Map<String, Double> counts = new TreeMap<>();
        for (Counter counter : registry.find(COUNTER).counters()) {
            counts.merge(counter.getId().getTag("stage"), counter.count(), Double::sum);
        }
        return counts;
    }

    private static MockMvc mockMvc(AssertableWebApplicationContext context) {
        assertThat(context).hasNotFailed();
        return MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext()).build();
    }

    /** Sends a trusted request with {@code Boundrail-Deadline-Ms: 0} through the library's inbound filter and returns the status. */
    private static int expiredOnArrival(AssertableWebApplicationContext context) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Boundrail-Deadline-Ms", "0");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new MockFilterChain(new jakarta.servlet.http.HttpServlet() { }, ContextInboundFilterTest.inboundRegistration(context).getFilter())
                .doFilter(request, response);
        return response.getStatus();
    }

    /** A client from the context's auto-configured builder, which carries the library's interceptor and the JDK factory. */
    private static RestClient client(AssertableWebApplicationContext context, RecordingServer server) {
        return context.getBean(RestClient.Builder.class).baseUrl(server.uri()).apply(BoundrailClients.internal("orders")).build();
    }

    @Test
    @DisplayName("DL-09 each expiry is counted once, where it is detected, tagged with its stage in lowercase")
    void eachExpiryIsCountedOnceWhereItIsDetected() {
        try (RecordingServer fast = RecordingServer.start("ok"); RecordingServer slow = RecordingServer.startDelayed("ok", Duration.ofSeconds(3))) {
            runner(fast).withUserConfiguration(OneRegistry.class).run(context -> {
                MeterRegistry registry = context.getBean(MeterRegistry.class);

                assertThat(expiredOnArrival(context)).isEqualTo(504);
                Throwable beforeSend = catchThrowable(() -> Context.with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ZERO))
                        .run(() -> client(context, fast).get().uri("/").retrieve().toBodilessEntity()));
                Throwable timeout = catchThrowable(() -> Context.with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ofMillis(300)))
                        .run(() -> client(context, slow).get().uri("/").retrieve().toBodilessEntity()));
                MvcResult refused = Context.with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ZERO))
                        .call(() -> mockMvc(context).perform(get("/callable")).andReturn());
                Object callableResult = refused.getAsyncResult(TIMEOUT_MILLIS);

                assertThat(beforeSend).isInstanceOf(DeadlineExceededException.class);
                assertThat(timeout).isInstanceOf(DeadlineExceededException.class);
                assertThat(callableResult).isInstanceOf(DeadlineExceededException.class);
                assertThat(counts(registry)).containsExactly(Map.entry("before_send", 1.0), Map.entry("executor", 1.0),
                        Map.entry("inbound", 1.0), Map.entry("timeout", 1.0));
            });
        }
    }

    @Test
    @DisplayName("DL-09 the exception mapping counts only APPLICATION expiries")
    void theExceptionMappingCountsOnlyApplicationExpiries() {
        try (RecordingServer partner = RecordingServer.start("ok")) {
            runner(partner).withUserConfiguration(OneRegistry.class).run(context -> {
                MeterRegistry registry = context.getBean(MeterRegistry.class);
                MockMvc mockMvc = mockMvc(context);

                int application = mockMvc.perform(get("/application")).andReturn().getResponse().getStatus();
                Map<String, Double> afterApplication = counts(registry);
                int beforeSend = mockMvc.perform(get("/before-send")).andReturn().getResponse().getStatus();

                assertThat(application).isEqualTo(504);
                assertThat(afterApplication).containsExactly(Map.entry("application", 1.0));
                assertThat(beforeSend).isEqualTo(504);
                assertThat(counts(registry)).as("the mapped BEFORE_SEND expiry was counted where it was detected, not again")
                        .containsExactly(Map.entry("application", 1.0), Map.entry("before_send", 1.0));
            });
        }
    }

    @Test
    @DisplayName("DL-09 expiries detected by BoundExecutors are not counted")
    void expiriesDetectedByBoundExecutorsAreNotCounted() {
        try (RecordingServer partner = RecordingServer.start("ok")) {
            runner(partner).withUserConfiguration(OneRegistry.class).run(context -> {
                MeterRegistry registry = context.getBean(MeterRegistry.class);
                ExecutorService pool = Executors.newSingleThreadExecutor();
                try {
                    Throwable bound = catchThrowable(() -> Context.with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ZERO))
                            .call(() -> BoundExecutors.attached(pool).submit(() -> "ran")).get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
                    MvcResult refused = Context.with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ZERO))
                            .call(() -> mockMvc(context).perform(get("/callable")).andReturn());
                    refused.getAsyncResult(TIMEOUT_MILLIS);

                    assertThat(bound).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(DeadlineExceededException.class);
                    assertThat(counts(registry)).as("only the Callable the Spring module refused").containsExactly(Map.entry("executor", 1.0));
                } finally {
                    pool.shutdownNow();
                }
            });
        }
    }

    @Test
    @DisplayName("DL-09 the counter uses the unique or primary MeterRegistry, never the global registry, and nothing fails without one")
    void theCounterUsesTheUniqueOrPrimaryMeterRegistry() {
        try (RecordingServer partner = RecordingServer.start("ok")) {
            runner(partner).withUserConfiguration(PrimaryAndOther.class).run(context -> {
                assertThat(expiredOnArrival(context)).isEqualTo(504);

                assertThat(counts(context.getBean("primaryRegistry", MeterRegistry.class))).containsExactly(Map.entry("inbound", 1.0));
                assertThat(counts(context.getBean("otherRegistry", MeterRegistry.class))).isEmpty();
            });
            runner(partner).withUserConfiguration(TwoWithoutPrimary.class).run(context -> {
                assertThat(expiredOnArrival(context)).isEqualTo(504);

                assertThat(counts(context.getBean("firstRegistry", MeterRegistry.class))).isEmpty();
                assertThat(counts(context.getBean("secondRegistry", MeterRegistry.class))).isEmpty();
            });
            runner(partner).run(context -> assertThat(expiredOnArrival(context)).as("no registry bean").isEqualTo(504));
            runner(partner).withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                    .run(context -> assertThat(expiredOnArrival(context)).as("without Micrometer").isEqualTo(504));
        }

        assertThat(Metrics.globalRegistry.find(COUNTER).counters()).as("never the global registry").isEmpty();
    }
}
