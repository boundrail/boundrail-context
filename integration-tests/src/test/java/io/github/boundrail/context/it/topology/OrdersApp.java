package io.github.boundrail.context.it.topology;

import io.github.boundrail.context.it.topology.Topology.Service;
import io.github.boundrail.context.spring.BoundrailClients;
import io.github.boundrail.context.test.TestInboundTrustVerifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/** Test service {@code orders}: internal service. */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@RestController
public class OrdersApp {

    private final ObservedContext observed;
    private final Topology.Running topology;
    private final RestClient internalBilling;

    /** The billing client is built from orders' auto-configured {@code RestClient.Builder}, so it carries the library's interceptor. */
    public OrdersApp(ObservedContext observed, Topology.Running topology, RestClient.Builder clients) {
        this.observed = observed;
        this.topology = topology;
        this.internalBilling = clients.clone().apply(BoundrailClients.internal("billing")).apply(TestInboundTrustVerifier.peer()).build();
    }

    @GetMapping("/ping")
    String ping(@RequestHeader HttpHeaders headers) {
        observed.record("orders", ObservedContext.ping(headers));
        return "orders";
    }

    /** Records on arrival, then pings billing through an internal client with the same marker (IT-3.1). */
    @GetMapping("/call-billing")
    String callBilling(@RequestHeader HttpHeaders headers) {
        observed.record("orders", ObservedContext.ping(headers));
        internalBilling.get().uri(topology.uri(Service.BILLING).resolve("/ping"))
                .header(EdgeApp.MARKER, headers.getFirst(EdgeApp.MARKER))
                .retrieve().toBodilessEntity();
        return "orders";
    }
}
