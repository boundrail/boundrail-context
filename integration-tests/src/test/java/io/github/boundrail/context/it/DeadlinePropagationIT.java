package io.github.boundrail.context.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.it.topology.EdgeApp;
import io.github.boundrail.context.it.topology.ObservedContext;
import io.github.boundrail.context.it.topology.Topology;
import io.github.boundrail.context.it.topology.Topology.Service;
import io.github.boundrail.context.test.TestInboundTrustVerifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestClient;

/**
 * Deadline propagation suite (IT-3): the remaining budget shrinks across hops, expired work fails fast, and deadlines stop at detached
 * boundaries. Scenarios come from specs/005-deadlines (IT-3.1 – IT-3.7) and specs/007-kafka (IT-3.8); each body is written test-first when
 * that feature enables it (BLD-10).
 */
@ExtendWith(Topology.class)
class DeadlinePropagationIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /**
     * This class's own edge, with a 500ms {@code default-inbound} and {@code attachedExecutor} in attached mode (G5): the shared edge has no
     * default, so other suites' requests carry no deadline.
     */
    private static ConfigurableApplicationContext edge;

    @BeforeAll
    static void startEdge(Topology.Running topology) throws Exception {
        edge = topology.startEdge("boundrail.context.deadline.default-inbound=500ms",
                "boundrail.context.executors.modes.attachedExecutor=attached");
        // Warm-up: class loading and the first JWT verification must not eat the timed requests' budget.
        callEdge(topology, "/ping", UUID.randomUUID().toString());
        callEdge(topology, "/deadline/chain", UUID.randomUUID().toString());
    }

    @AfterAll
    static void closeEdge() {
        if (edge != null) {
            edge.close();
        }
    }

    @Test
    @DisplayName("IT-3.1 Budget shrinks across hops")
    void it31BudgetShrinksAcrossHops(Topology.Running topology) throws Exception {
        // IT-3.1: Given a public request authenticated as alice, when edge calls orders through an internal client and orders calls billing
        // through an internal client, then the remaining budgets that edge, orders and billing record on arrival satisfy
        // `500ms ≥ edge > orders > billing > 0`. *(DL-03, DL-05)*
        String marker = UUID.randomUUID().toString();

        int status = callEdge(topology, "/deadline/chain", marker);

        assertThat(status).isEqualTo(200);
        long edgeNanos = remainingNanos(topology, "edge", marker);
        long ordersNanos = remainingNanos(topology, "orders", marker);
        long billingNanos = remainingNanos(topology, "billing", marker);
        assertThat(edgeNanos).as("edge").isLessThanOrEqualTo(500_000_000L).isGreaterThan(ordersNanos);
        assertThat(ordersNanos).as("orders").isGreaterThan(billingNanos);
        assertThat(billingNanos).as("billing").isPositive();
    }

    @Test
    @Disabled("Pending: M4")
    @DisplayName("IT-3.2 Slow external call fails fast")
    void it32SlowExternalCallFailsFast() {
        // IT-3.2: Given a billing client for partner, declared `external()` and built on the JDK `HttpClient` request factory with a
        // configured read timeout of 10s, when a trusted caller sends `Boundrail-Deadline-Ms: 500` to a billing handler that calls partner's
        // endpoint that sleeps for 5s, then the call fails with `DeadlineExceededException` (stage `TIMEOUT`) no later than the budget
        // remaining when it was sent plus 200ms, partner receives no `Boundrail-Deadline-Ms` header, and billing responds with 504.
        // *(DL-05, DL-07, DL-08, OUT-04)*
    }

    @Test
    @DisplayName("IT-3.3 Expired before send")
    void it33ExpiredBeforeSend(Topology.Running topology) throws Exception {
        // IT-3.3: When an edge handler serving a request authenticated as alice sleeps past its deadline and then calls orders, then
        // `DeadlineExceededException` (stage `BEFORE_SEND`) is thrown, orders receives no request, and edge responds with 504.
        // *(DL-06, DL-08)*
        String marker = UUID.randomUUID().toString();

        int status = callEdge(topology, "/deadline/sleep-then-call-orders", marker);

        assertThat(status).isEqualTo(504);
        assertThat(observed(topology, "edge-deadline", marker)).containsEntry("stage", "BEFORE_SEND");
        assertThat(topology.observed().of("orders")).as("orders receives no request")
                .noneMatch(seen -> marker.equals(seen.get(EdgeApp.MARKER.toLowerCase(Locale.ROOT))));
    }

    @Test
    @DisplayName("IT-3.4 Expired on arrival")
    void it34ExpiredOnArrival(Topology.Running topology) {
        // IT-3.4: When a trusted caller sends `Boundrail-Deadline-Ms: 0` to orders, then orders responds with 504 and its handler is not
        // invoked. *(DL-04)*
        String marker = UUID.randomUUID().toString();
        // A trusted caller: the test's own client, with the test peer header (TEST-01).
        RestClient orders = RestClient.builder().baseUrl(topology.uri(Service.ORDERS).toString())
                .apply(TestInboundTrustVerifier.peer()).build();

        int status = orders.get().uri("/ping").header(EdgeApp.MARKER, marker).header("Boundrail-Deadline-Ms", "0")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(504);
        assertThat(topology.observed().of("orders")).as("orders' handler is not invoked")
                .noneMatch(seen -> marker.equals(seen.get(EdgeApp.MARKER.toLowerCase(Locale.ROOT))));
    }

    @Test
    @DisplayName("IT-3.5 Inbound cap")
    void it35InboundCap(Topology.Running topology) {
        // IT-3.5: When a trusted caller sends `Boundrail-Deadline-Ms: 600000` to orders, then orders records a deadline with at most 30s
        // remaining. *(DL-03)*
        String marker = UUID.randomUUID().toString();
        // A trusted caller: the test's own client, with the test peer header (TEST-01).
        RestClient orders = RestClient.builder().baseUrl(topology.uri(Service.ORDERS).toString())
                .apply(TestInboundTrustVerifier.peer()).build();

        orders.get().uri("/ping").header(EdgeApp.MARKER, marker).header("Boundrail-Deadline-Ms", "600000").retrieve().toBodilessEntity();

        assertThat(observed(topology, "orders", marker)).hasEntrySatisfying(ObservedContext.DEADLINE_REMAINING_NANOS,
                nanos -> assertThat(Long.parseLong(nanos)).isPositive().isLessThanOrEqualTo(30_000_000_000L));
    }

    @Test
    @DisplayName("IT-3.6 Untrusted deadline ignored")
    void it36UntrustedDeadlineIgnored(Topology.Running topology) throws Exception {
        // IT-3.6: When a public caller sends `Boundrail-Deadline-Ms: 5` to edge, then edge reports an `UNTRUSTED_HEADER` violation for it,
        // records a deadline with more than 5ms and at most 500ms remaining (the default), and the request succeeds. *(DL-03, IN-02)*
        String marker = UUID.randomUUID().toString();
        // The JVM is shared, so the count is a delta around this request on this class's own edge.
        double before = untrustedDeadlineHeaders();

        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(topology.uri(edge).resolve("/ping"))
                        .header("Authorization", "Bearer " + topology.jwts().alice())
                        .header(EdgeApp.MARKER, marker)
                        .header("Boundrail-Deadline-Ms", "5")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        // Nanoseconds, recorded on arrival: above the ignored 5ms, at most the 500ms default.
        assertThat(observed(topology, "edge", marker)).hasEntrySatisfying(ObservedContext.DEADLINE_REMAINING_NANOS,
                nanos -> assertThat(Long.parseLong(nanos)).isGreaterThan(5_000_000L).isLessThanOrEqualTo(500_000_000L));
        assertThat(untrustedDeadlineHeaders()).isEqualTo(before + 1);
    }

    @Test
    @Disabled("Pending: M4")
    @DisplayName("IT-3.7 Attached versus detached executors")
    void it37AttachedVersusDetachedExecutors() {
        // IT-3.7: Within a public request authenticated as alice (tenant `acme`), which has edge's 500ms default deadline:
        // - a task on `attachedExecutor`, an edge `ThreadPoolTaskExecutor` bean configured with
        //   `executors.modes.attachedExecutor=attached`, observes the deadline;
        // - a task on `taskExecutor` (detached) observes no deadline but does observe `tenant=acme`;
        // - a task submitted to `boundAttachedExecutor`, an edge bean declared as `BoundExecutors.attached(...)` around a single-thread
        //   `ExecutorService`, while that thread is blocked until the deadline has expired, is not run, and its future's `get` throws
        //   `ExecutionException` whose cause is `DeadlineExceededException` (stage `EXECUTOR`).
        // *(EXE-01, EXE-02, EXE-04, EXE-08, POL-04)*
    }

    @Test
    @Disabled("Pending: M6")
    @DisplayName("IT-3.8 Kafka event outlives the request")
    void it38KafkaEventOutlivesTheRequest() {
        // IT-3.8: When edge publishes an event to `orders.events` during a request with a 500ms deadline, and billing's listener starts
        // processing it at least 4 seconds later, then processing succeeds, and billing observes no deadline, `tenant=acme` and
        // `initiator=user:alice`. *(KAF-01, KAF-02, KAF-04)*
    }

    /** Calls an endpoint of this class's edge as alice with the marker, and returns the status. */
    private static int callEdge(Topology.Running topology, String path, String marker) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(topology.uri(edge).resolve(path))
                        .header("Authorization", "Bearer " + topology.jwts().alice())
                        .header(EdgeApp.MARKER, marker)
                        .build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** This class's edge's {@code boundrail.context.violations{kind=untrusted_header,key=boundrail.deadline}} count. */
    private static double untrustedDeadlineHeaders() {
        return edge.getBean(MeterRegistry.class).find("boundrail.context.violations")
                .tags("kind", "untrusted_header", "key", "boundrail.deadline")
                .counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    /** The remaining nanoseconds a service recorded on arrival of the request with this marker. */
    private static long remainingNanos(Topology.Running topology, String service, String marker) {
        Map<String, String> seen = observed(topology, service, marker);
        assertThat(seen).as("%s recorded a deadline", service).containsKey(ObservedContext.DEADLINE_REMAINING_NANOS);
        return Long.parseLong(seen.get(ObservedContext.DEADLINE_REMAINING_NANOS));
    }

    /** The single request a service observed with this marker. */
    private static Map<String, String> observed(Topology.Running topology, String service, String marker) {
        List<Map<String, String>> marked = topology.observed().of(service).stream()
                .filter(seen -> marker.equals(seen.get(EdgeApp.MARKER.toLowerCase(Locale.ROOT))))
                .toList();
        assertThat(marked).as("%s requests marked %s", service, marker).hasSize(1);
        return marked.getFirst();
    }
}
