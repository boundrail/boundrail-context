package io.github.boundrail.context.it.topology;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Test service {@code partner}: external stub that records every header it receives. */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@RestController
public class PartnerApp {

    private final ObservedContext observed;

    public PartnerApp(ObservedContext observed) {
        this.observed = observed;
    }

    @GetMapping("/ping")
    String ping(@RequestHeader HttpHeaders headers) {
        observed.record("partner", ObservedContext.headers(headers));
        return "partner";
    }
}
