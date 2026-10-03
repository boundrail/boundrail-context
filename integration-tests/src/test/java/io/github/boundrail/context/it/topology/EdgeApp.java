package io.github.boundrail.context.it.topology;

import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextViolationException;
import io.github.boundrail.context.Initiator;
import io.github.boundrail.context.it.topology.Topology.Service;
import io.github.boundrail.context.spring.BoundrailClients;
import io.github.boundrail.context.spring.JwtClaimContributor;
import io.github.boundrail.context.test.TestInboundTrustVerifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Test service {@code edge}: public entry point; an OAuth2 resource server that accepts JWTs signed with the run's
 * {@link TestJwts} key.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@RestController
public class EdgeApp {

    /** Header edge copies from its {@code marker} parameter to its outbound calls, so tests find their own observations. */
    public static final String MARKER = "X-Marker";

    private final ObservedContext observed;
    private final Topology.Running topology;
    private final RestClient internalOrders;
    private final RestClient externalPartner;
    private final RestClient undeclaredPartner;

    /**
     * Every client is built from edge's auto-configured {@code RestClient.Builder}, so it carries the library's interceptor. Only internal
     * clients apply {@code TestInboundTrustVerifier.peer()}, so partner never receives the test peer header.
     */
    public EdgeApp(ObservedContext observed, Topology.Running topology, RestClient.Builder clients) {
        this.observed = observed;
        this.topology = topology;
        this.internalOrders = clients.clone().apply(BoundrailClients.internal("orders")).apply(TestInboundTrustVerifier.peer()).build();
        this.externalPartner = clients.clone().apply(BoundrailClients.external()).build();
        this.undeclaredPartner = clients.clone().build();
    }

    /** Verifies tokens with the run's public key; no network JWKS. */
    @Bean
    static JwtDecoder jwtDecoder(TestJwts jwts) {
        return NimbusJwtDecoder.withPublicKey(jwts.publicKey()).build();
    }

    /** Contributes the JWT's {@code tenant} claim, bound after authentication (IN-04, IN-06). */
    @Bean
    static JwtClaimContributor tenantClaimContributor() {
        return JwtClaimContributor.of("tenant", AppKeys.TENANT, Codecs.string());
    }

    @GetMapping("/ping")
    String ping(@RequestHeader HttpHeaders headers) {
        observed.record("edge", ObservedContext.ping(headers));
        return "edge";
    }

    /** Calls orders through an internal client, then partner through an {@code external()} client. */
    @GetMapping("/call-partner/external")
    String callPartnerExternal(@RequestParam("marker") String marker) {
        return callOrdersThenPartner(externalPartner, marker);
    }

    /** Calls orders through an internal client, then partner through a client with no declaration. */
    @GetMapping("/call-partner/undeclared")
    String callPartnerUndeclared(@RequestParam("marker") String marker) {
        return callOrdersThenPartner(undeclaredPartner, marker);
    }

    /** Calls partner through an {@code external()} client with {@code Tenant-Id} and {@code Authorization} set by application code. */
    @GetMapping("/call-partner/forbidden-headers")
    String callPartnerWithForbiddenHeaders(@RequestParam("marker") String marker) {
        externalPartner.get().uri(topology.uri(Service.PARTNER).resolve("/ping"))
                .header(MARKER, marker)
                .header("Tenant-Id", "acme")
                .header(HttpHeaders.AUTHORIZATION, "Bearer partner-key")
                .retrieve().toBodilessEntity();
        return "edge";
    }

    /** Binds {@code tenant=acme}, then calls orders through an internal client. */
    @GetMapping("/call-orders")
    String callOrders(@RequestParam("marker") String marker) {
        return Context.with(AppKeys.TENANT, "acme").call(() -> {
            ping(internalOrders, Service.ORDERS, marker);
            return "edge";
        });
    }

    /** Binds nothing itself, then calls orders through an internal client. */
    @GetMapping("/call-orders/as-caller")
    String callOrdersAsCaller(@RequestParam("marker") String marker) {
        ping(internalOrders, Service.ORDERS, marker);
        return "edge";
    }

    /**
     * Binds {@code tenant}, then calls orders inside {@code Context.without(tenant)}; returns the kind of the
     * {@code ContextViolationException} the call throws, or {@code sent} if it throws none.
     */
    @GetMapping("/call-orders/without-tenant")
    String callOrdersWithoutTenant(@RequestParam("marker") String marker) {
        return Context.with(AppKeys.TENANT, "acme").call(() -> Context.without(AppKeys.TENANT).call(() -> {
            try {
                ping(internalOrders, Service.ORDERS, marker);
                return "sent";
            } catch (ContextViolationException e) {
                return e.violation().kind().name();
            }
        }));
    }

    private String callOrdersThenPartner(RestClient partner, String marker) {
        return Context.with(AppKeys.TENANT, "acme")
                .with(AppKeys.PRIORITY, 5L)
                .with(BuiltInKeys.INITIATOR, Initiator.of("user:alice"))
                .call(() -> {
                    ping(internalOrders, Service.ORDERS, marker);
                    ping(partner, Service.PARTNER, marker);
                    return "edge";
                });
    }

    private void ping(RestClient client, Service service, String marker) {
        client.get().uri(topology.uri(service).resolve("/ping")).header(MARKER, marker).retrieve().toBodilessEntity();
    }
}
