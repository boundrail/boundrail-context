package io.github.boundrail.context.demo;

import io.github.boundrail.context.Violation;
import io.github.boundrail.context.demo.EdgeService.DeadlineCall;
import io.github.boundrail.context.demo.EdgeService.ExternalCall;
import java.io.PrintStream;
import java.net.URI;
import org.springframework.web.client.RestClient;

/** Calls {@code edge} and prints what the services observed. */
final class DemoClient {

    private final RestClient edge;
    private final PrintStream out;

    DemoClient(URI edge, PrintStream out) {
        this.edge = RestClient.create(edge);
        this.out = out;
    }

    /**
     * Demo 2: warms the services up, then prints what {@code edge} set on the call to {@code partner}, what arrived, and what {@code edge} reported.
     *
     * @throws IllegalStateException if {@code partner} received the tenant, or {@code edge} did not report it; nothing is printed then
     */
    void externalCall() {
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
     * Demo 3: warms the services up, then prints the budget left on arrival at {@code edge} and at {@code orders}.
     *
     * @throws IllegalStateException if the budget did not shrink from {@code edge} to {@code orders}, or {@code orders} had none; nothing is
     *     printed then
     */
    void deadline() {
        warmUp("/deadline");
        DeadlineCall call = edge.get().uri("/deadline").retrieve().body(DeadlineCall.class);
        if (call.edgeMillis() <= call.ordersMillis() || call.ordersMillis() <= 0) {
            throw new IllegalStateException("the budget did not shrink: edge %d ms, orders %d ms".formatted(call.edgeMillis(), call.ordersMillis()));
        }
        out.println("Demo 3: a deadline shrinks across hops, and a Kafka event is unaffected");
        out.printf("  edge      %d ms left%n", call.edgeMillis());
        out.printf("  orders    %d ms left%n", call.ordersMillis());
    }

    /** Calls a flow once and discards the result, even an error status, because a cold JVM can use up the budget. */
    private void warmUp(String path) {
        edge.get().uri(path).exchange((request, response) -> response.getStatusCode());
    }
}
