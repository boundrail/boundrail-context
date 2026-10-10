package io.github.boundrail.context.it.topology;

import io.github.boundrail.context.BoundExecutors;
import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextViolationException;
import io.github.boundrail.context.Deadline;
import io.github.boundrail.context.DeadlineExceededException;
import io.github.boundrail.context.Deadlines;
import io.github.boundrail.context.Initiator;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.it.topology.Topology.Service;
import io.github.boundrail.context.spring.BoundrailClients;
import io.github.boundrail.context.spring.JwtClaimContributor;
import io.github.boundrail.context.test.TestInboundTrustVerifier;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
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
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Test service {@code edge}: public entry point; an OAuth2 resource server that accepts JWTs signed with the run's
 * {@link TestJwts} key.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EnableAsync
@EnableScheduling
@RestController
public class EdgeApp {

    /** Header edge copies from its {@code marker} parameter to its outbound calls, so tests find their own observations. */
    public static final String MARKER = "X-Marker";

    /** The name under which edge's interceptor records what it observed in {@code postHandle} of an {@code ASYNC} dispatch (IT-1.9). */
    public static final String ASYNC_DISPATCH = "edge-async-dispatch";

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

    /** A {@code @Scheduled} job that records its context (IT-1.7); Boot's scheduler runs it on {@code scheduling-N} threads. */
    @Bean
    static CleanupJob cleanupJob(ObservedContext observed) {
        return new CleanupJob(observed);
    }

    /**
     * Registers a {@code HandlerInterceptor} that, in {@code postHandle} of an {@code ASYNC} dispatch, records the request's {@code marker}
     * parameter and the bound {@code tenant} under {@link #ASYNC_DISPATCH} (IT-1.9).
     */
    @Bean
    static WebMvcConfigurer asyncDispatchRecorder(ObservedContext observed) {
        HandlerInterceptor recorder = new HandlerInterceptor() {
            @Override
            public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
                String marker = request.getParameter("marker");
                if (request.getDispatcherType() == DispatcherType.ASYNC && marker != null) {
                    Map<String, String> observation = new HashMap<>();
                    observation.put(MARKER.toLowerCase(Locale.ROOT), marker);
                    AppKeys.TENANT.find().ifPresent(tenant -> observation.put(ObservedContext.TENANT, tenant));
                    observed.record(ASYNC_DISPATCH, observation);
                }
            }
        };
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(recorder);
            }
        };
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

    /**
     * Sleeps until the request's deadline has expired, then calls orders' {@code /ping} through the internal client with the marker; on
     * {@code DeadlineExceededException} records its stage under {@code edge-deadline} with the marker, and rethrows it (IT-3.3).
     */
    @GetMapping("/deadline/sleep-then-call-orders")
    String sleepThenCallOrders(@RequestHeader HttpHeaders headers) throws InterruptedException {
        String marker = headers.getFirst(MARKER);
        Deadline deadline = Deadlines.current().orElseThrow();
        Thread.sleep(deadline.remaining().plusMillis(20));
        try {
            ping(internalOrders, Service.ORDERS, marker);
            return "sent";
        } catch (DeadlineExceededException e) {
            observed.record("edge-deadline", Map.of(MARKER.toLowerCase(Locale.ROOT), marker, "stage", e.stage().name()));
            throw e;
        }
    }

    /** Records on arrival, then calls orders' {@code /call-billing} through the internal client with the same marker (IT-3.1). */
    @GetMapping("/deadline/chain")
    String deadlineChain(@RequestHeader HttpHeaders headers) {
        observed.record("edge", ObservedContext.ping(headers));
        internalOrders.get().uri(topology.uri(Service.ORDERS).resolve("/call-billing"))
                .header(MARKER, headers.getFirst(MARKER))
                .retrieve().toBodilessEntity();
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

    /**
     * Runs {@link EdgeAsyncWork#logLine} from a new thread with no context, so the task is submitted to {@code taskExecutor} without
     * context, and waits for it; returns the name of the thread it ran on.
     */
    @GetMapping("/async/log-without-context")
    String asyncLogWithoutContext(@RequestParam("marker") String marker) throws Exception {
        CompletableFuture<CompletableFuture<String>> submitted = new CompletableFuture<>();
        new Thread(() -> submitted.complete(asyncWork.logLine(marker))).start();
        return submitted.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);
    }

    /** Returns a {@code Callable} that calls orders; it completes with the name of the thread it ran on (IT-1.9). */
    @GetMapping("/mvc-async/callable")
    Callable<String> mvcCallableCallOrders(@RequestParam("marker") String marker) {
        return () -> {
            ping(internalOrders, Service.ORDERS, marker);
            return Thread.currentThread().getName();
        };
    }

    /** Returns a {@code DeferredResult} that a new thread with no context completes (IT-1.9). */
    @GetMapping("/mvc-async/deferred")
    DeferredResult<String> mvcDeferred(@RequestParam("marker") String marker) {
        DeferredResult<String> result = new DeferredResult<>();
        new Thread(() -> result.setResult("edge")).start();
        return result;
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
