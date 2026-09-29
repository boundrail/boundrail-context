package io.github.boundrail.context.it.topology;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Test service {@code token-exchange}: stub RFC 8693 token endpoint that counts calls. */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@RestController
public class TokenExchangeApp {

    private final ObservedContext observed;
    private final AtomicInteger calls = new AtomicInteger();

    public TokenExchangeApp(ObservedContext observed) {
        this.observed = observed;
    }

    @PostMapping("/token")
    void token(@RequestHeader HttpHeaders headers) {
        calls.incrementAndGet();
        observed.record("token-exchange", ObservedContext.headers(headers));
    }

    /** How many times the token endpoint has been called. */
    int calls() {
        return calls.get();
    }
}
