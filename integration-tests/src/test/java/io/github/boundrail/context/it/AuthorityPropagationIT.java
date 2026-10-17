package io.github.boundrail.context.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.Violation;
import io.github.boundrail.context.it.topology.EdgeApp;
import io.github.boundrail.context.it.topology.ObservedContext;
import io.github.boundrail.context.it.topology.Topology;
import io.github.boundrail.context.it.topology.Topology.Service;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

/**
 * Authority propagation suite (IT-4): the edge establishes authority and initiator, authority reaches only internal calls (forwarded or
 * exchanged), and it never leaks to external calls, detached work, messages or outputs. Scenarios come from specs/006-identity (IT-4.1 –
 * IT-4.8) and specs/007-kafka (IT-4.9); each body is written test-first when that feature enables it (BLD-10).
 */
@ExtendWith(Topology.class)
class AuthorityPropagationIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** An extra edge that exchanges alice's token at the token-exchange stub for internal calls (IT-4.3). */
    private static ConfigurableApplicationContext exchangeEdge;

    @BeforeAll
    static void startExchangeEdge(Topology.Running topology) {
        exchangeEdge = topology.startEdge("boundrail.context.authority.propagation=exchange",
                "boundrail.context.authority.exchange.token-uri=" + topology.uri(Service.TOKEN_EXCHANGE).resolve("/token"),
                "boundrail.context.authority.exchange.client-id=edge",
                "boundrail.context.authority.exchange.client-secret=edge-secret");
    }

    @AfterAll
    static void closeExchangeEdge() {
        if (exchangeEdge != null) {
            exchangeEdge.close();
        }
    }

    @Test
    @DisplayName("IT-4.1 Edge establishes authority and initiator")
    void it41EdgeEstablishesAuthorityAndInitiator(Topology.Running topology) throws Exception {
        // IT-4.1: When a public request authenticated as alice reaches an edge handler, then the handler observes
        // `authority.subject=alice` and `initiator=user:alice`. *(ID-02, ID-08)*
        String marker = UUID.randomUUID().toString();

        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(topology.uri(Service.EDGE).resolve("/ping"))
                .header("Authorization", "Bearer " + topology.jwts().alice()).header(EdgeApp.MARKER, marker).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(observed(topology, "edge", marker))
                .containsEntry(ObservedContext.AUTHORITY_SUBJECT, "alice")
                .containsEntry(ObservedContext.INITIATOR, "user:alice");
    }

    @Test
    @DisplayName("IT-4.2 Forward mode")
    void it42ForwardMode(Topology.Running topology) throws Exception {
        // IT-4.2: Given a public request authenticated as alice and `authority.propagation=forward`, when edge calls orders through its
        // `internal("orders")` client, then orders receives `Authorization: Bearer <alice's token>` and observes `initiator=user:alice`.
        // *(ID-03, ID-04)*
        String marker = UUID.randomUUID().toString();
        String token = topology.jwts().alice();

        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(topology.uri(Service.EDGE)
                        .resolve("/call-orders/as-caller?marker=" + marker)).header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(observed(topology, "orders", marker))
                .containsEntry("authorization", "Bearer " + token)
                .containsEntry(ObservedContext.INITIATOR, "user:alice");
    }

    @Test
    @DisplayName("IT-4.3 Exchange mode")
    void it43ExchangeMode(Topology.Running topology) throws Exception {
        // IT-4.3: Given a public request authenticated as alice to the exchange edge, when edge calls orders twice through its
        // `internal("orders")` client, then orders receives, both times, `Authorization: Bearer` followed by the token the stub issued for
        // the audience `orders`, never alice's token, and the stub receives exactly one request. *(ID-03, ID-04, ID-11)*
        String marker = UUID.randomUUID().toString();
        String token = topology.jwts().alice();
        int callsBefore = topology.tokenExchangeCalls();

        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(topology.uri(exchangeEdge)
                        .resolve("/authority/call-orders/twice?marker=" + marker)).header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(topology.tokenExchangeCalls()).as("the stub's calls").isEqualTo(callsBefore + 1);
        List<Map<String, String>> exchanges = topology.observed().of("token-exchange").stream()
                .filter(seen -> token.equals(seen.get("form.subject_token")))
                .toList();
        assertThat(exchanges).as("exchanges of alice's token").hasSize(1);
        Map<String, String> exchange = exchanges.getFirst();
        assertThat(exchange)
                .containsEntry("form.audience", "orders")
                .containsEntry("authorization", "Basic " + Base64.getEncoder().encodeToString(
                        "edge:edge-secret".getBytes(StandardCharsets.US_ASCII)));
        List<Map<String, String>> calls = observedAll(topology, "orders", marker);
        assertThat(calls).as("orders' observations marked %s", marker).hasSize(2)
                .allSatisfy(seen -> assertThat(seen).containsEntry("authorization", "Bearer " + exchange.get("issued")))
                .noneSatisfy(seen -> assertThat(seen.get("authorization")).contains(token));
    }

    @Test
    @DisplayName("IT-4.4 No verified credential, no authority")
    void it44NoVerifiedCredentialNoAuthority(Topology.Running topology) throws Exception {
        // IT-4.4: When a public caller without a token calls an edge endpoint that permits unauthenticated requests and sends
        // `Boundrail-Initiator: user:mallory`, then the handler observes neither an authority nor an initiator, and exactly one
        // `UNTRUSTED_HEADER` violation is reported, for the key `boundrail.initiator`. *(ID-02, ID-08, IN-02)*
        String marker = UUID.randomUUID().toString();
        double total = topology.violations(Service.EDGE, Violation.Kind.UNTRUSTED_HEADER);
        double before = topology.violations(Service.EDGE, Violation.Kind.UNTRUSTED_HEADER, "boundrail.initiator");

        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(topology.uri(Service.EDGE).resolve("/public/ping"))
                .header("Boundrail-Initiator", "user:mallory").header(EdgeApp.MARKER, marker).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(observed(topology, "edge", marker)).doesNotContainKeys(ObservedContext.AUTHORITY_SUBJECT, ObservedContext.INITIATOR);
        assertThat(topology.violations(Service.EDGE, Violation.Kind.UNTRUSTED_HEADER, "boundrail.initiator")).isEqualTo(before + 1);
        assertThat(topology.violations(Service.EDGE, Violation.Kind.UNTRUSTED_HEADER)).isEqualTo(total + 1);
    }

    @Test
    @DisplayName("IT-4.5 Authority never leaves")
    void it45AuthorityNeverLeaves(Topology.Running topology) throws Exception {
        // IT-4.5: Given a public request authenticated as alice, when edge calls partner through its `external()` client, then partner
        // receives no `Authorization` header. When edge runs work on `taskExecutor` (detached), then the task observes no authority and does
        // observe `initiator=user:alice`. *(ID-03, ID-06, POL-04)*
        String marker = UUID.randomUUID().toString();

        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(topology.uri(Service.EDGE)
                        .resolve("/authority/detached?marker=" + marker)).header("Authorization", "Bearer " + topology.jwts().alice()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(observed(topology, "edge-authority", marker))
                .containsEntry("detached-initiator", "user:alice")
                .containsEntry("detached-authority", "false");
        assertThat(observed(topology, "partner", marker)).doesNotContainKey("authorization");
    }

    @Test
    @Disabled("Pending: M5")
    @DisplayName("IT-4.6 No credential in any output")
    void it46NoCredentialInAnyOutput() {
        // IT-4.6: Across all IT-4 scenarios, the captured logs, the MDC, violation reports and every `toString()` of a snapshot never
        // contain alice's token string. *(ID-01, ID-06, LOG-02)*
    }

    @Test
    @DisplayName("IT-4.7 Application-set Authorization is respected")
    void it47ApplicationSetAuthorizationIsRespected(Topology.Running topology) throws Exception {
        // IT-4.7: Given a public request authenticated as alice, when edge application code sets `Authorization: Bearer service-token` on a
        // call through its `internal("orders")` client, then orders receives exactly `Authorization: Bearer service-token`. *(ID-03)*
        String marker = UUID.randomUUID().toString();

        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(topology.uri(Service.EDGE)
                        .resolve("/authority/call-orders/service-token?marker=" + marker))
                        .header("Authorization", "Bearer " + topology.jwts().alice()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(observed(topology, "orders", marker)).containsEntry("authorization", "Bearer service-token");
    }

    @Test
    @Disabled("Pending: M5")
    @DisplayName("IT-4.8 Exchange without an audience")
    void it48ExchangeWithoutAnAudience() {
        // IT-4.8: Given a public request authenticated as alice to the exchange edge, when edge calls `orders.svc.test` through a client with
        // no declaration (so it is classified `INTERNAL` by the host pattern `*.svc.test`, without a service name), then orders receives the
        // request without an `Authorization` header, the stub receives no request, and exactly one `MISSING_AUDIENCE` violation is reported.
        // *(ID-05)*
    }

    @Test
    @Disabled("Pending: M6")
    @DisplayName("IT-4.9 Messages carry the initiator, never the authority")
    void it49MessagesCarryTheInitiatorNeverTheAuthority() {
        // IT-4.9: When edge publishes an event during alice's request, then billing's listener observes `initiator=user:alice`, no
        // authority, and no `Authorization` record header exists. *(ID-06, KAF-04)*
    }

    /** The single observation a service recorded with this marker. */
    private static Map<String, String> observed(Topology.Running topology, String service, String marker) {
        List<Map<String, String>> marked = observedAll(topology, service, marker);
        assertThat(marked).as("%s observations marked %s", service, marker).hasSize(1);
        return marked.getFirst();
    }

    /** Every observation a service recorded with this marker, oldest first. */
    private static List<Map<String, String>> observedAll(Topology.Running topology, String service, String marker) {
        return topology.observed().of(service).stream()
                .filter(seen -> marker.equals(seen.get(EdgeApp.MARKER.toLowerCase(Locale.ROOT))))
                .toList();
    }
}
