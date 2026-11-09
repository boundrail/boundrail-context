package io.github.boundrail.context.demo;

import io.github.boundrail.context.Violation;
import io.github.boundrail.context.demo.EdgeService.DeadlineCall;
import io.github.boundrail.context.demo.EdgeService.ExternalCall;
import io.github.boundrail.context.demo.EdgeService.HandledEvent;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Calls {@code edge} and prints what the services observed. */
final class DemoClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    /** Longer than {@code edge}'s budget, so that it is {@code edge} that answers a slow call. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration WARM_UP_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient edge;
    private final PrintStream out;

    DemoClient(URI edge, PrintStream out) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requests.setReadTimeout(READ_TIMEOUT);
        this.edge = RestClient.builder().baseUrl(edge).requestFactory(requests).build();
        this.out = out;
    }

    public static void main(String[] args) throws InterruptedException {
        DemoClient client = new DemoClient(URI.create("http://edge:8080"), System.out);
        client.awaitEdge();
        System.out.println();
        client.externalCall();
        System.out.println();
        client.deadline();
    }

    /**
     * Calls {@code edge} until it answers, whatever the status.
     *
     * @throws IllegalStateException if it has not answered in time
     * @throws InterruptedException if interrupted between two calls
     */
    private void awaitEdge() throws InterruptedException {
        long giveUp = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < giveUp) {
            try {
                edge.get().uri("/").exchange((request, response) -> response.getStatusCode());
                return;
            } catch (ResourceAccessException notUpYet) {
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("edge did not answer within " + STARTUP_TIMEOUT);
    }

    /**
     * Demo 2: warms the services up, then prints what {@code edge} set on the call to {@code partner}, what arrived, and what {@code edge} reported.
     *
     * @throws IllegalStateException if no warm-up call succeeded, {@code partner} received the tenant, or {@code edge} did not report it; nothing
     *     is printed then
     * @throws InterruptedException if interrupted while warming up
     */
    void externalCall() throws InterruptedException {
        warmUp("/external-call");
        ExternalCall call = edge.get().uri("/external-call").retrieve().body(ExternalCall.class);
        if (call.partnerHeaders().keySet().stream().anyMatch("Tenant-Id"::equalsIgnoreCase)) {
            throw new IllegalStateException("partner received a Tenant-Id");
        }
        if (!call.reported().contains(Violation.Kind.FORBIDDEN_HEADER)) {
            throw new IllegalStateException("edge reported no FORBIDDEN_HEADER");
        }
        out.println("Demo 2: a tenant is stripped from an external call");
        out.printf("  edge      set Tenant-Id: %s on the call to partner%n", call.tenant());
        out.println("  partner   received no Tenant-Id");
        out.println("  edge      reported FORBIDDEN_HEADER");
    }

    /**
     * Demo 3: warms the services up, then prints the budget left on arrival at {@code edge}, the simulated work done there, the budget left on
     * arrival at {@code orders}, and what {@code edge}'s listener found when it handled the event {@code orders} published.
     *
     * @throws IllegalStateException if no warm-up call succeeded; if the budget did not shrink from {@code edge} to {@code orders}, or
     *     {@code orders} had none; or if the listener handled no event, handled it inside the budget, found a deadline or did not find the tenant
     *     {@code edge} bound; nothing is printed then
     * @throws InterruptedException if interrupted while warming up or waiting for the event
     */
    void deadline() throws InterruptedException {
        warmUp("/deadline");
        DeadlineCall call = edge.get().uri("/deadline").retrieve().body(DeadlineCall.class);
        if (call.edgeMillis() <= call.ordersMillis() || call.ordersMillis() <= 0) {
            throw new IllegalStateException("the budget did not shrink: edge %d ms, orders %d ms".formatted(call.edgeMillis(), call.ordersMillis()));
        }
        HandledEvent event = awaitEvent(call.event());
        if (event.deadlineBound()) {
            throw new IllegalStateException("the listener found a deadline");
        }
        if (!call.tenant().equals(event.tenant())) {
            throw new IllegalStateException("the listener found tenant %s, edge bound %s".formatted(event.tenant(), call.tenant()));
        }
        // The event was published after edge's budget started, so handling it a whole budget after publishing is past the deadline.
        if (event.millisAfterPublish() < call.edgeMillis()) {
            throw new IllegalStateException("the listener handled the event %d ms after publishing, inside the budget of %d ms"
                    .formatted(event.millisAfterPublish(), call.edgeMillis()));
        }
        out.println("Demo 3: a deadline shrinks across hops, and a Kafka event is unaffected");
        out.printf("  edge      %d ms left, then %d ms of simulated work%n", call.edgeMillis(), call.simulatedWorkMillis());
        out.printf("  orders    %d ms left%n", call.ordersMillis());
        out.printf("  event     handled after the deadline passed, tenant %s, no deadline%n", event.tenant());
    }

    /**
     * Asks {@code edge} until its listener has handled the event.
     *
     * @throws IllegalStateException if it has not handled the event in time, or answers with an error status
     * @throws InterruptedException if interrupted between two calls
     */
    private HandledEvent awaitEvent(String id) throws InterruptedException {
        long giveUp = System.nanoTime() + EVENT_TIMEOUT.toNanos();
        while (System.nanoTime() < giveUp) {
            HandledEvent event = edge.get().uri("/events/{id}", id).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new IllegalStateException("edge answered %s when asked for the event".formatted(response.getStatusCode()));
                }
                return response.bodyTo(HandledEvent.class);
            });
            if (event != null) {
                return event;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("edge handled no event within " + EVENT_TIMEOUT);
    }

    /**
     * Calls a flow until a call succeeds, and discards the results, because a cold JVM or a slow first Kafka send can use up the budget.
     *
     * @throws IllegalStateException if no call has succeeded in time; its message has the last status
     * @throws InterruptedException if interrupted between two calls
     */
    private void warmUp(String path) throws InterruptedException {
        long giveUp = System.nanoTime() + WARM_UP_TIMEOUT.toNanos();
        HttpStatusCode last = null;
        while (System.nanoTime() < giveUp) {
            last = edge.get().uri(path).exchange((request, response) -> response.getStatusCode());
            if (last.is2xxSuccessful()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("edge gave no successful call to %s within %s, last status %s".formatted(path, WARM_UP_TIMEOUT, last));
    }
}
