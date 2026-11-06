package io.github.boundrail.context.demo;

import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.spring.BoundrailClients;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/** Service {@code edge}: the demo's entry point. No component scan, so the services in this package do not pick each other up. */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class EdgeService {

    private final RestClient orders;

    EdgeService(RestClient.Builder clients) {
        orders = clients.clone().apply(BoundrailClients.internal("orders")).baseUrl("http://orders:8080").build();
    }

    @Bean
    static ContextPolicy applicationPolicy() {
        return DemoPolicy.policy();
    }

    String callOrders() {
        return orders.get().uri("/orders").retrieve().body(String.class);
    }
}
