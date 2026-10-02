package io.github.boundrail.context.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.Violation;
import io.github.boundrail.context.it.topology.EdgeApp;
import io.github.boundrail.context.it.topology.ObservedContext;
import io.github.boundrail.context.it.topology.Topology;
import io.github.boundrail.context.it.topology.Topology.Service;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Trust boundary suite (IT-2): context crosses HTTP only to trusted internal destinations and is bound only from trusted callers.
 * Scenarios come from specs/003-http-trust (IT-2.1 – IT-2.9); each body is written test-first when that feature enables it (BLD-10).
 */
@ExtendWith(Topology.class)
class TrustBoundaryIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Every context header M2 could send, as {@code ObservedContext} records names: lower case. */
    private static final String[] CONTEXT_HEADERS = {"tenant-id", "priority", "boundrail-initiator", "boundrail-deadline-ms"};

    @Test
    @Disabled("Pending: M2")
    @DisplayName("IT-2.1 Internal call carries tenant")
    void it21InternalCallCarriesTenant() {
        // IT-2.1: Given a public request authenticated as alice, when edge calls orders through a client declared `internal("orders")`,
        // then orders observes `tenant=acme`. *(IN-04, IN-06, OUT-03, OUT-04, WIRE-01)*
    }

    @Test
    @DisplayName("IT-2.2 External call carries no context")
    void it22ExternalCallCarriesNoContext(Topology.Running topology) throws Exception {
        // IT-2.2: When edge calls partner through a client declared `external()`, then partner receives none of `Tenant-Id`, `Priority`,
        // `Boundrail-Initiator` or `Boundrail-Deadline-Ms`. *(OUT-04)*
        String marker = UUID.randomUUID().toString();

        HttpResponse<String> response = callEdge(topology, "/call-partner/external", marker);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(observed(topology, "orders", marker)).containsEntry("tenant-id", "acme");
        assertThat(observed(topology, "partner", marker)).doesNotContainKeys(CONTEXT_HEADERS);
    }

    @Test
    @DisplayName("IT-2.3 Unknown means external")
    void it23UnknownMeansExternal(Topology.Running topology) throws Exception {
        // IT-2.3: When edge calls partner through a client with no declaration, whose host matches no pattern, then the result is the
        // same as IT-2.2. *(OUT-02, OUT-03, DEST-02, DEST-03)*
        String marker = UUID.randomUUID().toString();

        HttpResponse<String> response = callEdge(topology, "/call-partner/undeclared", marker);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(topology.uri(Service.PARTNER).getHost()).isEqualTo("partner.example.test");
        assertThat(observed(topology, "orders", marker)).containsEntry("tenant-id", "acme");
        assertThat(observed(topology, "partner", marker)).doesNotContainKeys(CONTEXT_HEADERS);
    }

    @Test
    @DisplayName("IT-2.4 Application-set forbidden headers are stripped")
    void it24ApplicationSetForbiddenHeadersAreStripped(Topology.Running topology) throws Exception {
        // IT-2.4: When edge application code sets `Tenant-Id: acme` and `Authorization: Bearer partner-key` on a call declared external,
        // then partner receives `Authorization: Bearer partner-key` and no `Tenant-Id`, and one `FORBIDDEN_HEADER` violation is
        // reported. *(WIRE-07)*
        String marker = UUID.randomUUID().toString();
        double before = topology.violations(Service.EDGE, Violation.Kind.FORBIDDEN_HEADER);

        HttpResponse<String> response = callEdge(topology, "/call-partner/forbidden-headers", marker);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(observed(topology, "partner", marker))
                .containsEntry("authorization", "Bearer partner-key")
                .doesNotContainKey("tenant-id");
        // The topology is shared, so the count is a delta around this request.
        assertThat(topology.violations(Service.EDGE, Violation.Kind.FORBIDDEN_HEADER)).isEqualTo(before + 1);
    }

    @Test
    @Disabled("Pending: M2")
    @DisplayName("IT-2.5 Forged tenant from a public caller is ignored")
    void it25ForgedTenantFromAPublicCallerIsIgnored() {
        // IT-2.5: When a public caller authenticated as alice sends `Tenant-Id: evil`, then the edge handler observes `tenant=acme` (from
        // the JWT claim, bound after authentication), one `UNTRUSTED_HEADER` violation is reported, and the request succeeds.
        // *(IN-02, IN-04, IN-05)*
    }

    @Test
    @Disabled("Pending: M2")
    @DisplayName("IT-2.6 Forged tenant with no claim")
    void it26ForgedTenantWithNoClaim() {
        // IT-2.6: When a public caller whose JWT has no `tenant` claim sends `Tenant-Id: evil`, then `tenant` is unbound in the edge
        // handler. *(IN-02, IN-06)*
    }

    @Test
    @DisplayName("IT-2.7 Trusted caller's header is accepted")
    void it27TrustedCallersHeaderIsAccepted(Topology.Running topology) throws Exception {
        // IT-2.7: When edge (a trusted caller) sends `Tenant-Id: acme` to orders, then orders binds `tenant=acme`.
        // *(IN-02, IN-03, TEST-01)*
        String marker = UUID.randomUUID().toString();

        HttpResponse<String> response = callEdge(topology, "/call-orders", marker);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(observed(topology, "orders", marker))
                .containsEntry("tenant-id", "acme")
                .containsEntry(ObservedContext.TENANT, "acme");
    }

    @Test
    @Disabled("Pending: M2")
    @DisplayName("IT-2.8 Invalid and oversized values")
    void it28InvalidAndOversizedValues() {
        // IT-2.8: When a trusted caller sends `Priority: abc`, then orders leaves `priority` unbound and one `INVALID_VALUE` violation is
        // reported. When it sends a 300-character `Tenant-Id`, then orders leaves `tenant` unbound and one `OVERSIZED_VALUE` violation is
        // reported. Both requests succeed. *(WIRE-04, WIRE-05)*
    }

    @Test
    @DisplayName("IT-2.9 Required key missing on an internal call")
    void it29RequiredKeyMissingOnAnInternalCall(Topology.Running topology) throws Exception {
        // IT-2.9: Given violation mode `fail`, when edge code runs `Context.without(tenant)` and calls orders, then
        // `ContextViolationException` is thrown and orders receives no request. *(POL-08, DIAG-02)*
        String marker = UUID.randomUUID().toString();
        // The topology is shared, so the counts are deltas around this request.
        double before = topology.violations(Service.EDGE, Violation.Kind.MISSING_REQUIRED);
        int ordersBefore = topology.observed().of("orders").size();

        HttpResponse<String> response = callEdge(topology, "/call-orders/without-tenant", marker);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).as("the kind of the ContextViolationException edge caught").isEqualTo("MISSING_REQUIRED");
        assertThat(topology.violations(Service.EDGE, Violation.Kind.MISSING_REQUIRED)).isEqualTo(before + 1);
        assertThat(topology.observed().of("orders")).hasSize(ordersBefore);
    }

    /** Calls an edge endpoint as alice, the public caller. */
    private static HttpResponse<String> callEdge(Topology.Running topology, String path, String marker) throws Exception {
        URI uri = topology.uri(Service.EDGE).resolve(path + "?marker=" + marker);
        return HTTP.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + topology.jwts().alice()).build(),
                HttpResponse.BodyHandlers.ofString());
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
