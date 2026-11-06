package io.github.boundrail.context.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextBinding;
import io.github.boundrail.context.Deadline;
import io.github.boundrail.context.demo.EdgeService.DeadlineCall;
import io.github.boundrail.context.demo.EdgeService.HandledEvent;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.web.client.RestClient;

/** Demo 3 of design.md §15: a deadline shrinks across hops, and a Kafka event is unaffected. */
class DeadlineDemoTest {

    /** With {@code trust=none}, {@code orders} has no tenant, and in the default {@code fail} mode its send would fail before the budget check runs. */
    private static final String UNTRUSTED_ORDERS_STILL_PUBLISH = "--boundrail.context.violations.mode=report";

    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();

    @Test
    @DisplayName("REL-02 demo 3 the deadline shrinks from edge to orders")
    void theDeadlineShrinksFromEdgeToOrders() throws InterruptedException {
        try (DemoServices services = DemoServices.start()) {
            client(services).deadline();
        }

        List<String> lines = printed.toString().lines().toList();
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).isEqualTo("Demo 3: a deadline shrinks across hops, and a Kafka event is unaffected");
        assertThat(lines.get(1)).matches("  edge      \\d+ ms left");
        assertThat(lines.get(2)).matches("  orders    \\d+ ms left");
        assertThat(budget(lines.get(1))).isGreaterThan(budget(lines.get(2)));
        assertThat(budget(lines.get(2))).isPositive();
    }

    @Test
    @DisplayName("REL-02 demo 3 a Kafka event handled after the deadline keeps the tenant and carries no deadline")
    void aKafkaEventHandledAfterTheDeadlineKeepsTheTenant() throws InterruptedException {
        try (DemoServices services = DemoServices.start()) {
            client(services).deadline();
        }

        assertThat(printed.toString().lines().toList()).hasSize(4)
                .last().isEqualTo("  event     handled after the deadline passed, tenant acme, no deadline");
    }

    @Test
    @DisplayName("REL-02 demo 3 a run in which the listener handled no event fails")
    void aRunInWhichTheListenerHandledNoEventFails() {
        try (DemoServices services = DemoServices.startWithEdge("--spring.kafka.listener.auto-startup=false")) {
            DemoClient client = client(services);

            assertThatThrownBy(client::deadline).isInstanceOf(IllegalStateException.class).hasMessageContaining("handled no event");
        }

        assertThat(printed.toString()).isEmpty();
    }

    @Test
    @DisplayName("REL-02 demo 3 a run in which the listener found no tenant fails")
    void aRunInWhichTheListenerFoundNoTenantFails() {
        try (DemoServices services = DemoServices.startWithEdge("--boundrail.context.kafka.trust-headers=false")) {
            DemoClient client = client(services);

            assertThatThrownBy(client::deadline).isInstanceOf(IllegalStateException.class).hasMessageContaining("found tenant null");
        }

        assertThat(printed.toString()).isEmpty();
    }

    @Test
    @DisplayName("REL-02 demo 3 a run in which the listener found another tenant fails")
    void aRunInWhichTheListenerFoundAnotherTenantFails() {
        try (DemoServices services = DemoServices.start(ListenerFindsAnotherTenant.class)) {
            DemoClient client = client(services);

            assertThatThrownBy(client::deadline).isInstanceOf(IllegalStateException.class).hasMessageContaining("found tenant globex");
        }

        assertThat(printed.toString()).isEmpty();
    }

    @Test
    @DisplayName("REL-02 demo 3 a run in which the listener found an expired deadline fails")
    void aRunInWhichTheListenerFoundADeadlineFails() {
        try (DemoServices services = DemoServices.start(ListenerFindsAnExpiredDeadline.class)) {
            DemoClient client = client(services);

            assertThatThrownBy(client::deadline).isInstanceOf(IllegalStateException.class).hasMessageContaining("found a deadline");
        }

        assertThat(printed.toString()).isEmpty();
    }

    @Test
    @DisplayName("REL-02 demo 3 a run in which the listener handled the event inside the budget fails")
    void aRunInWhichTheListenerHandledTheEventInsideTheBudgetFails() {
        try (DemoServices services = DemoServices.startWithEdge("--demo.event-wait=0s")) {
            DemoClient client = client(services);

            assertThatThrownBy(client::deadline).isInstanceOf(IllegalStateException.class).hasMessageContaining("inside the budget");
        }

        assertThat(printed.toString()).isEmpty();
    }

    @Test
    @DisplayName("REL-02 demo 3 a run is judged on its own event, not the warm-up's")
    void aRunIsJudgedOnItsOwnEvent() {
        try (DemoServices services = DemoServices.start(OnlyTheFirstEventKeepsItsTenant.class)) {
            DemoClient client = client(services);

            assertThatThrownBy(client::deadline).isInstanceOf(IllegalStateException.class).hasMessageContaining("found tenant globex");
        }

        assertThat(printed.toString()).isEmpty();
    }

    @Test
    @DisplayName("REL-02 demo 3 a consumer group that joins after the event was published still handles it")
    void aConsumerGroupThatJoinsLateStillHandlesTheEvent() {
        String event;
        try (DemoServices services = DemoServices.start()) {
            event = firstBody(services, "/deadline", DeadlineCall.class).event();
        }

        try (DemoServices services = DemoServices.startWithEdge("--spring.kafka.consumer.group-id=" + UUID.randomUUID())) {
            assertThat(firstBody(services, "/events/" + event, HandledEvent.class).tenant()).isEqualTo("acme");
        }
    }

    @Test
    @DisplayName("REL-02 demo 3 a run in which the events endpoint fails reports its status")
    void aRunInWhichTheEventsEndpointFailsReportsItsStatus() {
        try (DemoServices services = DemoServices.start(EventsEndpointFails.class)) {
            DemoClient client = client(services);

            assertThatThrownBy(client::deadline).isInstanceOf(IllegalStateException.class).hasMessageContaining("503");
        }

        assertThat(printed.toString()).isEmpty();
    }

    @Test
    @DisplayName("REL-02 demo 3 a run in which orders received no deadline fails")
    void aRunInWhichOrdersReceivedNoDeadlineFails() {
        try (DemoServices services = DemoServices.startWithOrders(UNTRUSTED_ORDERS_STILL_PUBLISH, "--boundrail.context.inbound.trust=none")) {
            DemoClient client = client(services);

            assertThatThrownBy(client::deadline).isInstanceOf(IllegalStateException.class).hasMessageContaining("orders 0 ms");
        }

        assertThat(printed.toString()).isEmpty();
    }

    @Test
    @DisplayName("REL-02 demo 3 a run in which the budget did not shrink at orders fails")
    void aRunInWhichTheBudgetDidNotShrinkFails() {
        try (DemoServices services = DemoServices.startWithOrders(UNTRUSTED_ORDERS_STILL_PUBLISH, "--boundrail.context.inbound.trust=none",
                "--boundrail.context.deadline.default-inbound=1h")) {
            DemoClient client = client(services);

            assertThatThrownBy(client::deadline).isInstanceOf(IllegalStateException.class).hasMessageContaining("did not shrink");
        }

        assertThat(printed.toString()).isEmpty();
    }

    @Test
    @DisplayName("REL-02 demo 3 a first call that runs out of budget is discarded as the warm-up")
    void aFirstCallThatRunsOutOfBudgetIsDiscarded() throws InterruptedException {
        try (DemoServices services = DemoServices.start(DemoServices.FirstCallTimesOut.class)) {
            client(services).deadline();
        }

        assertThat(printed.toString().lines().toList()).hasSize(4);
    }

    private DemoClient client(DemoServices services) {
        return new DemoClient(services.edge(), new PrintStream(printed, true));
    }

    private static long budget(String line) {
        return Long.parseLong(line.replaceAll("\\D", ""));
    }

    /** {@return the first non-empty body of a successful response from {@code edge}}; fails after a timeout */
    private static <T> T firstBody(DemoServices services, String path, Class<T> type) {
        RestClient edge = RestClient.create(services.edge());
        return await().atMost(Duration.ofSeconds(30)).until(() -> edge.get().uri(path).exchange((request, response) ->
                response.getStatusCode().is2xxSuccessful() ? response.bodyTo(type) : null), Objects::nonNull);
    }

    /** Appends advice that runs the listener in the binding, inside the library's scope, which comes first in the chain (KAF-03). */
    private static void bindAroundTheListener(AbstractKafkaListenerContainerFactory<?, ?, ?> factory, ContextBinding binding) {
        appendAdvice(factory, invocation -> binding.call(invocation::proceed));
    }

    private static void appendAdvice(AbstractKafkaListenerContainerFactory<?, ?, ?> factory, MethodInterceptor interceptor) {
        Advice[] chain = factory.getContainerProperties().getAdviceChain();
        Advice[] appended = Arrays.copyOf(chain, chain.length + 1);
        appended[chain.length] = interceptor;
        factory.getContainerProperties().setAdviceChain(appended);
    }

    /** Makes {@code edge}'s listener find a tenant other than the one {@code edge} bound. */
    @Configuration(proxyBeanMethods = false)
    static class ListenerFindsAnotherTenant {

        ListenerFindsAnotherTenant(@Qualifier("kafkaListenerContainerFactory") AbstractKafkaListenerContainerFactory<?, ?, ?> factory) {
            bindAroundTheListener(factory, Context.with(DemoPolicy.TENANT, "globex"));
        }
    }

    /**
     * Lets the first record value through as published and makes {@code edge}'s listener find another tenant on every other record. The warm-up
     * is published first, so the run's own event is never the first; a leftover record from an earlier run may be. The advice also wraps
     * listener methods that are not about a record, so it ignores those.
     */
    @Configuration(proxyBeanMethods = false)
    static class OnlyTheFirstEventKeepsItsTenant {

        OnlyTheFirstEventKeepsItsTenant(@Qualifier("kafkaListenerContainerFactory") AbstractKafkaListenerContainerFactory<?, ?, ?> factory) {
            AtomicReference<Object> first = new AtomicReference<>();
            appendAdvice(factory, invocation -> {
                Object[] arguments = invocation.getArguments();
                if (arguments.length > 0 && arguments[0] instanceof ConsumerRecord<?, ?> record) {
                    first.compareAndSet(null, record.value());
                    if (!record.value().equals(first.get())) {
                        return Context.with(DemoPolicy.TENANT, "globex").call(invocation::proceed);
                    }
                }
                return invocation.proceed();
            });
        }
    }

    /** Adds a filter to {@code edge} that answers every request for an event with a 503. */
    @Configuration(proxyBeanMethods = false)
    static class EventsEndpointFails {

        @Bean
        static Filter eventsEndpointFails() {
            return (request, response, chain) -> {
                if (((HttpServletRequest) request).getRequestURI().startsWith("/events/")) {
                    ((HttpServletResponse) response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                } else {
                    chain.doFilter(request, response);
                }
            };
        }
    }

    /** Makes {@code edge}'s listener find a deadline that has expired, so its budget reads 0, as it does with no deadline at all. */
    @Configuration(proxyBeanMethods = false)
    static class ListenerFindsAnExpiredDeadline {

        ListenerFindsAnExpiredDeadline(@Qualifier("kafkaListenerContainerFactory") AbstractKafkaListenerContainerFactory<?, ?, ?> factory) {
            bindAroundTheListener(factory, Context.with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ZERO)));
        }
    }
}
