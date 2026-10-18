package io.github.boundrail.context.it.topology;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test service {@code token-exchange}: stub RFC 8693 token endpoint that counts calls. Each call is recorded under {@code token-exchange}
 * with its headers, its form fields as {@code form.<name>} and the token it issued under {@code issued}.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@RestController
public class TokenExchangeApp {

    private final ObservedContext observed;
    private final AtomicInteger calls = new AtomicInteger();

    public TokenExchangeApp(ObservedContext observed) {
        this.observed = observed;
    }

    /** Answers 200 with a new access token that names the requested audience, valid for one hour. */
    @PostMapping("/token")
    Map<String, Object> token(@RequestHeader HttpHeaders headers, @RequestParam MultiValueMap<String, String> form) {
        calls.incrementAndGet();
        String issued = "exchanged-" + form.getFirst("audience") + "-" + UUID.randomUUID();
        Map<String, String> observation = new HashMap<>(ObservedContext.headers(headers));
        for (String field : new String[] {"grant_type", "subject_token", "subject_token_type", "audience"}) {
            String value = form.getFirst(field);
            if (value != null) {
                observation.put("form." + field, value);
            }
        }
        observation.put("issued", issued);
        observed.record("token-exchange", observation);
        return Map.of("access_token", issued,
                "issued_token_type", "urn:ietf:params:oauth:token-type:access_token",
                "token_type", "Bearer",
                "expires_in", 3600);
    }

    /** How many times the token endpoint has been called. */
    int calls() {
        return calls.get();
    }
}
