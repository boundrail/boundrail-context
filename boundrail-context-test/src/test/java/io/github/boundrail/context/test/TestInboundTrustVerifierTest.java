package io.github.boundrail.context.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.sun.net.httpserver.HttpServer;
import io.github.boundrail.context.spring.InboundTrustVerifier;
import io.github.boundrail.context.spring.InboundTrustVerifier.Trust;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class TestInboundTrustVerifierTest {

    private static final String PEER_HEADER = "Boundrail-Test-Peer";

    private HttpServer server;
    private final List<Map<String, List<String>>> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try (exchange; InputStream in = exchange.getRequestBody()) {
                in.readAllBytes();
                received.add(Map.copyOf(exchange.getRequestHeaders()));
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("TEST-01 TRUSTED_INTERNAL exactly when Boundrail-Test-Peer carries the secret once")
    void trustedInternalExactlyWhenTheHeaderCarriesTheSecretOnce() {
        String secret = peerHeaderValues(send(RestClient.builder().apply(TestInboundTrustVerifier.peer()).build())).getFirst();
        InboundTrustVerifier verifier = new TestInboundTrustVerifier();

        assertThat(verifier.verify(request(Map.of()))).as("absent").isEqualTo(Trust.UNTRUSTED);
        assertThat(verifier.verify(request(Map.of(PEER_HEADER, List.of("not-the-secret"))))).as("wrong value").isEqualTo(Trust.UNTRUSTED);
        assertThat(verifier.verify(request(Map.of(PEER_HEADER, List.of(secret))))).as("correct value").isEqualTo(Trust.TRUSTED_INTERNAL);
        assertThat(verifier.verify(request(Map.of(PEER_HEADER, List.of(secret, secret))))).as("correct value twice")
                .isEqualTo(Trust.UNTRUSTED);
    }

    @Test
    @DisplayName("TEST-01 every instance shares one secret")
    void everyInstanceSharesOneSecret() {
        HttpServletRequest throughPeer = request(send(RestClient.builder().apply(TestInboundTrustVerifier.peer()).build()));

        assertThat(new TestInboundTrustVerifier().verify(throughPeer)).isEqualTo(Trust.TRUSTED_INTERNAL);
        assertThat(new TestInboundTrustVerifier().verify(throughPeer)).isEqualTo(Trust.TRUSTED_INTERNAL);
    }

    @Test
    @DisplayName("TEST-01 peer() adds the header to every request of the client it configures")
    void peerAddsTheHeaderToEveryRequestOfTheClientItConfigures() {
        RestClient peer = RestClient.builder().apply(TestInboundTrustVerifier.peer()).build();
        RestClient plain = RestClient.builder().build();

        Map<String, List<String>> first = send(peer);
        Map<String, List<String>> second = send(peer);
        Map<String, List<String>> withoutPeer = send(plain);

        assertThat(peerHeaderValues(first)).hasSize(1).first().asString().isNotBlank();
        assertThat(peerHeaderValues(second)).isEqualTo(peerHeaderValues(first));
        assertThat(peerHeaderValues(withoutPeer)).isEmpty();
    }

    @Test
    @DisplayName("TEST-01 the verifier is not auto-configured")
    void theVerifierIsNotAutoConfigured() throws IOException {
        List<String> imports = resources("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        List<String> factories = resources("META-INF/spring.factories");

        assertThat(imports).as("the -spring imports file is on the classpath, so the scan is not empty")
                .anyMatch(content -> content.contains("io.github.boundrail.context.spring.BoundrailContextAutoConfiguration"));
        assertThat(imports).noneMatch(content -> content.contains(TestInboundTrustVerifier.class.getPackageName() + "."));
        assertThat(factories).noneMatch(content -> content.contains(TestInboundTrustVerifier.class.getPackageName() + "."));
    }

    @Test
    @DisplayName("CORE-13 the test verifier rejects a null request")
    void theTestVerifierRejectsANullRequest() {
        InboundTrustVerifier verifier = new TestInboundTrustVerifier();

        assertThatNullPointerException().isThrownBy(() -> verifier.verify(null));
    }

    /** Sends one real request to the JDK server and returns the headers it received. */
    private Map<String, List<String>> send(RestClient client) {
        int before = received.size();
        client.get().uri(URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/"))
                .retrieve().toBodilessEntity();
        assertThat(received).hasSize(before + 1);
        return received.get(before);
    }

    private static List<String> peerHeaderValues(Map<String, List<String>> headers) {
        return named(headers, PEER_HEADER);
    }

    /**
     * A request that answers only header lookups, with names matched ignoring case; every other method throws, so a verifier that reads
     * anything else fails the test instead of seeing a default.
     */
    private static HttpServletRequest request(Map<String, List<String>> headers) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHeaders" -> Collections.enumeration(named(headers, (String) args[0]));
                    case "getHeader" -> named(headers, (String) args[0]).stream().findFirst().orElse(null);
                    case "toString" -> "request" + headers.keySet();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** The values of a header, with its name matched ignoring case as an HTTP server does. */
    private static List<String> named(Map<String, List<String>> headers, String name) {
        List<String> values = new ArrayList<>();
        headers.forEach((header, list) -> {
            if (header.equalsIgnoreCase(name)) {
                values.addAll(list);
            }
        });
        return values;
    }

    private static List<String> resources(String name) throws IOException {
        List<String> contents = new ArrayList<>();
        for (URL url : Collections.list(TestInboundTrustVerifierTest.class.getClassLoader().getResources(name))) {
            try (InputStream in = url.openStream()) {
                contents.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return contents;
    }
}
