package io.github.boundrail.context.it.topology;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test service {@code edge}: public entry point; an OAuth2 resource server that accepts JWTs signed with the run's
 * {@link TestJwts} key.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@RestController
public class EdgeApp {

    private final ObservedContext observed;

    public EdgeApp(ObservedContext observed) {
        this.observed = observed;
    }

    /** Verifies tokens with the run's public key; no network JWKS. */
    @Bean
    static JwtDecoder jwtDecoder(TestJwts jwts) {
        return NimbusJwtDecoder.withPublicKey(jwts.publicKey()).build();
    }

    @GetMapping("/ping")
    String ping(@RequestHeader HttpHeaders headers) {
        observed.record("edge", ObservedContext.headers(headers));
        return "edge";
    }
}
