package io.github.boundrail.context.demo;

import io.github.boundrail.context.ContextPolicy;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service {@code orders}: an internal service that trusts {@code edge}'s network, so it binds the context headers {@code edge} sends. */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@PropertySource("classpath:orders.properties")
@RestController
public class OrdersService {

    @Bean
    static ContextPolicy applicationPolicy() {
        return DemoPolicy.policy();
    }

    /** Demo 3: returns the budget left on arrival. */
    @GetMapping("/orders")
    long orders() {
        return DemoPolicy.budgetMillis();
    }
}
