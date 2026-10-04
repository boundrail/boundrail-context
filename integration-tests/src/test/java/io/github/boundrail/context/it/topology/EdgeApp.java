package io.github.boundrail.context.it.topology;

import io.github.boundrail.context.BoundExecutors;
import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextViolationException;
import io.github.boundrail.context.Initiator;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.it.topology.Topology.Service;
import io.github.boundrail.context.spring.BoundrailClients;
import io.github.boundrail.context.spring.JwtClaimContributor;
import io.github.boundrail.context.test.TestInboundTrustVerifier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
@EnableAsync
@RestController
public class EdgeApp {

    /** Header edge copies from its {@code marker} parameter to its outbound calls, so tests find their own observations. */
    public static final String MARKER = "X-Marker";

    private final ObservedContext observed;
    private final Topology.Running topology;
    private final EdgeAsyncWork asyncWork;
    private final ExecutorService rawExecutor;
    private final ExecutorService boundExecutor;
    private final RestClient internalOrders;
    private final RestClient externalPartner;
    private final RestClient undeclaredPartner;

    /**
     * Every client is built from edge's auto-configured {@code RestClient.Builder}, so it carries the library's interceptor. Only internal
     * clients apply {@code TestInboundTrustVerifier.peer()}, so partner never receives the test peer header.
     */
    public EdgeApp(ObservedContext observed, Topology.Running topology, RestClient.Builder clients, EdgeAsyncWork asyncWork,
            @Qualifier("rawExecutor") ExecutorService rawExecutor, @Qualifier("boundExecutor") ExecutorService boundExecutor) {
        this.observed = observed;
        this.topology = topology;
        this.asyncWork = asyncWork;
        this.rawExecutor = rawExecutor;
        this.boundExecutor = boundExecutor;
        this.internalOrders = clients.clone().apply(BoundrailClients.internal("orders")).apply(TestInboundTrustVerifier.peer()).build();
        this.externalPartner = clients.clone().apply(BoundrailClients.external()).build();
        this.undeclaredPartner = clients.clone().build();
    }

    /** Verifies tokens with the run's public key; no network JWKS. */
    @Bean
    static JwtDecoder jwtDecoder(TestJwts jwts) {
        return NimbusJwtDecoder.withPublicKey(jwts.publicKey()).build();
    }

    /**
     * The executor {@code @Async} methods run on: a {@code ThreadPoolTaskExecutor} built by Boot's builder, so Boot applies Micrometer's
     * context-propagating decorator ({@code spring.task.execution.propagate-context=true}); the library decorates it (EXE-03). One thread, so
     * later tasks reuse it.
     */
    @Bean
    static ThreadPoolTaskExecutor taskExecutor(ThreadPoolTaskExecutorBuilder builder) {
        return builder.corePoolSize(1).maxPoolSize(1).threadNamePrefix("taskExecutor-").build();
    }

    /** A plain one-thread {@code ExecutorService} whose threads are named {@code rawExecutor-N}: not decorated (EXE-04), so a gap. */
    @Bean
    static ExecutorService rawExecutor() {
        AtomicInteger threads = new AtomicInteger();
        return Executors.newSingleThreadExecutor(task -> new Thread(task, "rawExecutor-" + threads.incrementAndGet()));
    }

    /** A one-thread {@code ExecutorService} wrapped by {@code BoundExecutors.detached} with the topology policy: covered (EXE-04). */
    @Bean
    static ExecutorService boundExecutor() {
        AtomicInteger threads = new AtomicInteger();
        return BoundExecutors.detached(
                Executors.newSingleThreadExecutor(task -> new Thread(task, "boundExecutor-" + threads.incrementAndGet())),
                TopologyPolicy.policy());
    }

    @Bean
    static EdgeAsyncWork edgeAsyncWork(Topology.Running topology, RestClient.Builder clients) {
        return new EdgeAsyncWork(topology, clients);
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

    /** Runs {@link EdgeAsyncWork#callOrders} and waits for it; returns the name of the thread it ran on. */
    @GetMapping("/async/call-orders")
    String asyncCallOrders(@RequestParam("marker") String marker) throws Exception {
        return asyncWork.callOrders(marker).get(10, TimeUnit.SECONDS);
    }

    /** Runs the same work as {@link #asyncCallOrders} on {@code rawExecutor} and waits for it; see {@link #callOrdersOn}. */
    @GetMapping("/raw/call-orders")
    String rawCallOrders(@RequestParam("marker") String marker) throws Exception {
        return callOrdersOn(rawExecutor, marker);
    }

    /** Runs the same work as {@link #asyncCallOrders} on {@code boundExecutor} and waits for it; see {@link #callOrdersOn}. */
    @GetMapping("/bound/call-orders")
    String boundCallOrders(@RequestParam("marker") String marker) throws Exception {
        return callOrdersOn(boundExecutor, marker);
    }

    /**
     * Calls orders on the executor and waits. Returns {@code sent}, or the fields of the {@code ContextViolationException} the call throws:
     * kind, key, boundary, capture site and executor evidence, separated by {@code |}, with {@code -} for an empty one.
     */
    private String callOrdersOn(ExecutorService executor, String marker) throws Exception {
        Future<?> work = executor.submit(() -> ping(internalOrders, Service.ORDERS, marker));
        try {
            work.get(10, TimeUnit.SECONDS);
            return "sent";
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ContextViolationException violationException) {
                Violation violation = violationException.violation();
                return String.join("|", violation.kind().name(), violation.key(), violation.boundary().name(),
                        violation.captureSite().orElse("-"), violation.executorEvidence().orElse("-"));
            }
            throw e;
        }
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
