package io.github.boundrail.context.it.topology;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Test service {@code orders}: internal service. */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@RestController
public class OrdersApp {

    private final ObservedContext observed;

    public OrdersApp(ObservedContext observed) {
        this.observed = observed;
    }

    @GetMapping("/ping")
    String ping(@RequestHeader HttpHeaders headers) {
        observed.record("orders", ObservedContext.headers(headers));
        return "orders";
    }
}
