package io.github.boundrail.context.demo;

import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.spring.BoundrailClients;
import io.github.boundrail.context.spring.ViolationListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/** Service {@code edge}: the demo's entry point. No component scan, so the services in this package do not pick each other up. */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@RestController
public class EdgeService {

    private final RestClient orders;
    private final RestClient partner;
    private final ReportedViolations reported;

    EdgeService(RestClient.Builder clients, ReportedViolations reported,
            @Value("${demo.partner-url:http://partner:8080}") String partnerUrl) {
        orders = clients.clone().apply(BoundrailClients.internal("orders")).baseUrl("http://orders:8080").build();
        partner = clients.clone().apply(BoundrailClients.external()).baseUrl(partnerUrl).build();
        this.reported = reported;
    }

    @Bean
    static ContextPolicy applicationPolicy() {
        return DemoPolicy.policy();
    }

    @Bean
    static ReportedViolations reportedViolations() {
        return new ReportedViolations();
    }

    String callOrders() {
        return orders.get().uri("/orders").retrieve().body(String.class);
    }

    /** Demo 2: sets {@code Tenant-Id} on a call declared external, and returns what {@code partner} received and what was reported. */
    @GetMapping("/external-call")
    ExternalCall externalCall() {
        // A real edge takes the tenant from the verified token: see step 3 of the README's quick start.
        return Context.with(DemoPolicy.TENANT, "acme").call(() -> {
            String tenant = DemoPolicy.TENANT.get();
            Map<String, String> received = partner.get().uri("/headers").header("Tenant-Id", tenant).retrieve()
                    .body(new ParameterizedTypeReference<>() { });
            return new ExternalCall(tenant, received, reported.drain());
        });
    }

    /** What {@code edge} returns for demo 2. */
    record ExternalCall(String tenant, Map<String, String> partnerHeaders, List<Violation.Kind> reported) {
    }

    /**
     * Keeps the kinds of the reported violations until a handler returns them. The queue is service-wide and assumes one client at a time, and
     * a failed call leaves its entries for the next response.
     */
    static class ReportedViolations implements ViolationListener {

        private final Queue<Violation.Kind> kinds = new ConcurrentLinkedQueue<>();

        @Override
        public void onViolation(Violation violation) {
            kinds.add(violation.kind());
        }

        private List<Violation.Kind> drain() {
            List<Violation.Kind> drained = new ArrayList<>();
            for (Violation.Kind kind = kinds.poll(); kind != null; kind = kinds.poll()) {
                drained.add(kind);
            }
            return drained;
        }
    }
}
