package io.github.boundrail.context.it.topology;

import io.github.boundrail.context.DeadlineExceededException;
import io.github.boundrail.context.Deadlines;
import io.github.boundrail.context.it.topology.Topology.Service;
import io.github.boundrail.context.spring.BoundrailClients;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/** Test service {@code billing}: internal service. */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@RestController
public class BillingApp {

    private final ObservedContext observed;
    private final Topology.Running topology;
    private final RestClient externalPartner;

    /**
     * The partner client is built from billing's auto-configured {@code RestClient.Builder}, so it carries the library's interceptor and uses
     * Boot's request factory: the JDK {@code HttpClient} one with a 10s read timeout, set by {@code Topology} (IT-3.2).
     */
    public BillingApp(ObservedContext observed, Topology.Running topology, RestClient.Builder clients) {
        this.observed = observed;
        this.topology = topology;
        this.externalPartner = clients.clone().apply(BoundrailClients.external()).build();
    }

    @GetMapping("/ping")
    String ping(@RequestHeader HttpHeaders headers) {
        observed.record("billing", ObservedContext.ping(headers));
        return "billing";
    }

    /**
     * Calls partner's {@code /slow} through the {@code external()} client with the marker, and records under {@code billing-partner-call} the
     * remaining time before the call, the time the call took, and the stage of a {@code DeadlineExceededException}, which it rethrows
     * (IT-3.2).
     */
    @GetMapping("/call-partner/slow")
    String callPartnerSlow(@RequestHeader HttpHeaders headers) {
        String marker = headers.getFirst(EdgeApp.MARKER);
        Map<String, String> observation = new HashMap<>();
        observation.put(EdgeApp.MARKER.toLowerCase(Locale.ROOT), marker);
        Deadlines.current().ifPresent(deadline -> observation.put("remaining-nanos", Long.toString(deadline.remaining().toNanos())));
        long start = System.nanoTime();
        try {
            externalPartner.get().uri(topology.uri(Service.PARTNER).resolve("/slow")).header(EdgeApp.MARKER, marker)
                    .retrieve().toBodilessEntity();
            return "billing";
        } catch (DeadlineExceededException e) {
            observation.put("stage", e.stage().name());
            throw e;
        } finally {
            observation.put("elapsed-nanos", Long.toString(System.nanoTime() - start));
            observed.record("billing-partner-call", observation);
        }
    }
}
