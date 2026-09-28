package io.github.boundrail.context.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.it.topology.TestJwts;
import io.github.boundrail.context.it.topology.Topology;
import io.github.boundrail.context.it.topology.Topology.Service;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@ExtendWith(Topology.class)
class TopologySmokeIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    @DisplayName("BLD-07 edge accepts alice's JWT through edge.svc.test")
    void edgeAcceptsAlicesJwt(Topology.Running topology) throws Exception {
        String marker = UUID.randomUUID().toString();
        URI edge = topology.uri(Service.EDGE).resolve("/ping");

        HttpResponse<String> response = send(HttpRequest.newBuilder(edge)
                .header("Authorization", "Bearer " + topology.jwts().alice())
                .header("X-Smoke", marker)
                .build());

        assertThat(edge.getHost()).isEqualTo("edge.svc.test");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(topology.observed().of("edge")).anySatisfy(seen -> assertThat(seen).containsEntry("x-smoke", marker));
    }

    @Test
    @DisplayName("BLD-07 edge rejects a request without a JWT")
    void edgeRejectsRequestWithoutJwt(Topology.Running topology) throws Exception {
        String marker = UUID.randomUUID().toString();

        HttpResponse<String> response = send(HttpRequest.newBuilder(topology.uri(Service.EDGE).resolve("/ping"))
                .header("X-Smoke", marker)
                .build());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(topology.observed().of("edge")).noneSatisfy(seen -> assertThat(seen).containsEntry("x-smoke", marker));
    }

    @Test
    @DisplayName("BLD-07 edge rejects a JWT not signed with this run's key")
    void edgeRejectsJwtFromAnotherKey(Topology.Running topology) throws Exception {
        String foreignToken = new TestJwts().alice();

        HttpResponse<String> response = send(HttpRequest.newBuilder(topology.uri(Service.EDGE).resolve("/ping"))
                .header("Authorization", "Bearer " + foreignToken)
                .build());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("BLD-07 alice's test JWT carries the claim tenant=acme")
    void alicesJwtCarriesTenantAcme(Topology.Running topology) {
        TestJwts jwts = topology.jwts();

        Jwt alice = NimbusJwtDecoder.withPublicKey(jwts.publicKey()).build().decode(jwts.alice());

        assertThat(alice.getSubject()).isEqualTo("alice");
        assertThat(alice.getClaimAsString("tenant")).isEqualTo("acme");
    }

    @ParameterizedTest(name = "{displayName}: {0} at {1}")
    @CsvSource({
        "ORDERS,  orders.svc.test,      orders",
        "BILLING, billing.svc.test,     billing",
        "PARTNER, partner.example.test, partner",
    })
    @DisplayName("BLD-07 service answers through its hosts-file name and records the headers it received")
    void serviceAnswersThroughHostsFileName(Service service, String host, String name, Topology.Running topology)
            throws Exception {
        String marker = UUID.randomUUID().toString();
        URI uri = topology.uri(service).resolve("/ping");

        HttpResponse<String> response = send(HttpRequest.newBuilder(uri).header("X-Smoke", marker).build());

        assertThat(uri.getHost()).isEqualTo(host);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(topology.observed().of(name)).anySatisfy(seen -> assertThat(seen).containsEntry("x-smoke", marker));
    }

    @Test
    @DisplayName("BLD-07 token-exchange answers through token-exchange.svc.test and counts the call")
    void tokenExchangeCountsCalls(Topology.Running topology) throws Exception {
        URI uri = topology.uri(Service.TOKEN_EXCHANGE).resolve("/token");
        int before = topology.tokenExchangeCalls();

        HttpResponse<String> response = send(HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange&audience=orders"))
                .build());

        assertThat(uri.getHost()).isEqualTo("token-exchange.svc.test");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(topology.tokenExchangeCalls()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("BLD-07 Testcontainers Kafka broker is reachable at its advertised address")
    void kafkaBrokerIsReachable(Topology.Running topology) throws Exception {
        try (Admin admin = Admin.create(
                Map.<String, Object>of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, topology.kafkaBootstrapServers()))) {

            assertThat(admin.describeCluster().nodes().get(30, TimeUnit.SECONDS)).isNotEmpty();
        }
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
