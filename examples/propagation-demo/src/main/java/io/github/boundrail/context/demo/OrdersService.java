package io.github.boundrail.context.demo;

import io.github.boundrail.context.ContextPolicy;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service {@code orders}: an internal service that trusts {@code edge}'s network, so it binds the context headers {@code edge} sends. */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@PropertySource("classpath:orders.properties")
@RestController
public class OrdersService {

    private final KafkaTemplate<String, String> kafka;

    public static void main(String[] args) {
        SpringApplication.run(OrdersService.class, args);
    }

    OrdersService(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    @Bean
    static ContextPolicy applicationPolicy() {
        return DemoPolicy.policy();
    }

    /** Demo 3: publishes an event, whose value identifies it, and returns the budget left on arrival. */
    @GetMapping("/orders")
    OrderPlaced orders() {
        long budget = DemoPolicy.budgetMillis();
        String event = UUID.randomUUID().toString();
        kafka.send("orders.events", event);
        return new OrderPlaced(budget, event);
    }

    /** What {@code orders} returns; a budget of 0 means no deadline, or under 1 ms left. */
    record OrderPlaced(long budgetMillis, String event) {
    }
}
