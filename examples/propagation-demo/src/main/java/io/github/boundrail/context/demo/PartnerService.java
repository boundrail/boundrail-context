package io.github.boundrail.context.demo;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Service {@code partner}: the fake external service. It returns the headers it received, so the demo shows what left {@code edge}. */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@RestController
public class PartnerService {

    public static void main(String[] args) {
        SpringApplication.run(PartnerService.class, args);
    }

    @GetMapping("/headers")
    Map<String, String> headers(@RequestHeader HttpHeaders headers) {
        return headers.toSingleValueMap();
    }
}
