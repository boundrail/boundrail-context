package io.github.boundrail.context.demo;

import io.github.boundrail.context.Violation;
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
     * Demo 2: prints what {@code edge} set on the call to {@code partner}, what arrived, and what {@code edge} reported.
     *
     * @throws IllegalStateException if {@code partner} received the tenant, or {@code edge} did not report it; nothing is printed then
     */
    void externalCall() {
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
}
