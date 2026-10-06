package io.github.boundrail.context.it.topology;

import io.github.boundrail.context.it.topology.Topology.Service;
import io.github.boundrail.context.spring.BoundrailClients;
import io.github.boundrail.context.test.TestInboundTrustVerifier;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.client.RestClient;

/**
 * Edge's async work: calls orders from an {@code @Async} method, which runs on the bean named {@code taskExecutor}. A separate bean from
 * {@link EdgeApp}, because a self-call would bypass the {@code @Async} proxy.
 */
public class EdgeAsyncWork {

    private static final Logger LOG = LoggerFactory.getLogger(EdgeAsyncWork.class);

    private final Topology.Running topology;
    private final RestClient internalOrders;

    public EdgeAsyncWork(Topology.Running topology, RestClient.Builder clients) {
        this.topology = topology;
        this.internalOrders = clients.clone().apply(BoundrailClients.internal("orders")).apply(TestInboundTrustVerifier.peer()).build();
    }

    /** Logs a line with the marker, then calls orders with it; completes with the name of the thread the work ran on. */
    @Async
    public CompletableFuture<String> callOrders(String marker) {
        LOG.info("Async work calling orders, marker {}", marker);
        internalOrders.get().uri(topology.uri(Service.ORDERS).resolve("/ping")).header(EdgeApp.MARKER, marker).retrieve().toBodilessEntity();
        return CompletableFuture.completedFuture(Thread.currentThread().getName());
    }

    /** Logs a line with the marker; completes with the name of the thread it ran on. */
    @Async
    public CompletableFuture<String> logLine(String marker) {
        LOG.info("Async work logging, marker {}", marker);
        return CompletableFuture.completedFuture(Thread.currentThread().getName());
    }
}
