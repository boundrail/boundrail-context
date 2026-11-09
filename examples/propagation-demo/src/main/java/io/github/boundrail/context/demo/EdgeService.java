package io.github.boundrail.context.demo;

import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.Deadlines;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.demo.OrdersService.OrderPlaced;
import io.github.boundrail.context.spring.BoundrailClients;
import io.github.boundrail.context.spring.ViolationListener;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/** Service {@code edge}: the demo's entry point. No component scan, so the services in this package do not pick each other up. */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@PropertySource("classpath:edge.properties")
@RestController
public class EdgeService {

    private final RestClient orders;
    private final RestClient partner;
    private final ReportedViolations reported;
    private final Duration eventWait;
    private final Duration simulatedWork;
    private final Map<String, HandledEvent> handledEvents = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        SpringApplication.run(EdgeService.class, args);
    }

    EdgeService(RestClient.Builder clients, ReportedViolations reported,
            @Value("${demo.orders-url:http://orders:8080}") String ordersUrl,
            @Value("${demo.partner-url:http://partner:8080}") String partnerUrl,
            @Value("${demo.event-wait}") Duration eventWait,
            @Value("${demo.simulated-work}") Duration simulatedWork) {
        orders = clients.clone().apply(BoundrailClients.internal("orders")).baseUrl(ordersUrl).build();
        partner = clients.clone().apply(BoundrailClients.external()).baseUrl(partnerUrl).build();
        this.reported = reported;
        this.eventWait = eventWait;
        this.simulatedWork = simulatedWork;
    }

    @Bean
    static ContextPolicy applicationPolicy() {
        return DemoPolicy.policy();
    }

    @Bean
    static ReportedViolations reportedViolations() {
        return new ReportedViolations();
    }

    OrderPlaced callOrders() {
        return orders.get().uri("/orders").retrieve().body(OrderPlaced.class);
    }

    /** Demo 2: sets {@code Tenant-Id} on a call declared external, and returns what {@code partner} received and what was reported. */
    @GetMapping("/external-call")
    ExternalCall externalCall() {
        // A real edge takes the tenant from the verified token: see step 3 of the README's quick start.
        return Context.with(DemoPolicy.TENANT, "acme").call(() -> {
            String tenant = DemoPolicy.TENANT.get();
            Map<String, String> received = partner.get().uri("/headers").header("Tenant-Id", tenant).retrieve()
                    .body(new ParameterizedTypeReference<>() { });
            return new ExternalCall(tenant, received, reported.drain());
        });
    }

    /**
     * Demo 3: returns the budget left on arrival here, the simulated work done before calling {@code orders}, the budget {@code orders} found on
     * arrival there, and the event {@code orders} published.
     */
    @GetMapping("/deadline")
    DeadlineCall deadline() throws InterruptedException {
        long budget = DemoPolicy.budgetMillis();
        return Context.with(DemoPolicy.TENANT, "acme").call(() -> {
            Thread.sleep(simulatedWork);
            OrderPlaced placed = callOrders();
            return new DeadlineCall(DemoPolicy.TENANT.get(), budget, simulatedWork.toMillis(), placed.budgetMillis(), placed.event());
        });
    }

    /** Demo 3: waits until the request's budget has passed since the event was published, then records what it finds bound. */
    @KafkaListener(topics = "orders.events")
    void onOrderEvent(ConsumerRecord<String, String> record) throws InterruptedException {
        long wait = record.timestamp() + eventWait.toMillis() - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
        handledEvents.put(record.value(), new HandledEvent(DemoPolicy.TENANT.find().orElse(null), Deadlines.current().isPresent(),
                System.currentTimeMillis() - record.timestamp()));
    }

    /** Demo 3: returns what the listener found for the event; an empty body until it has handled the event. */
    @GetMapping("/events/{event}")
    HandledEvent handledEvent(@PathVariable("event") String event) {
        return handledEvents.get(event);
    }

    /** What {@code edge} returns for demo 2. */
    record ExternalCall(String tenant, Map<String, String> partnerHeaders, List<Violation.Kind> reported) {
    }

    /** What {@code edge} returns for demo 3; a budget of 0 means that service had no deadline, or under 1 ms left. */
    record DeadlineCall(String tenant, long edgeMillis, long simulatedWorkMillis, long ordersMillis, String event) {
    }

    /** What the listener found bound; {@code tenant} is {@code null} if none was. */
    record HandledEvent(String tenant, boolean deadlineBound, long millisAfterPublish) {
    }

    /**
     * Keeps the kinds of the reported violations until a handler returns them. The queue is service-wide and assumes one client at a time, and
     * a failed call leaves its entries for the next response.
     */
    static class ReportedViolations implements ViolationListener {

        private final Queue<Violation.Kind> kinds = new ConcurrentLinkedQueue<>();

        @Override
        public void onViolation(Violation violation) {
            kinds.add(violation.kind());
        }

        private List<Violation.Kind> drain() {
            List<Violation.Kind> drained = new ArrayList<>();
            for (Violation.Kind kind = kinds.poll(); kind != null; kind = kinds.poll()) {
                drained.add(kind);
            }
            return drained;
        }
    }
}
