package io.github.boundrail.context.it.topology;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Test service {@code billing}: internal service. */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@RestController
public class BillingApp {

    private final ObservedContext observed;

    public BillingApp(ObservedContext observed) {
        this.observed = observed;
    }

    @GetMapping("/ping")
    String ping(@RequestHeader HttpHeaders headers) {
        observed.record("billing", ObservedContext.headers(headers));
        return "billing";
    }
}
