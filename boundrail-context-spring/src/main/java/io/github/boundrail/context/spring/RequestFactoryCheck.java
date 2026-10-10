package io.github.boundrail.context.spring;

import org.apache.commons.logging.Log;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.JdkClientHttpRequestFactoryBuilder;

/**
 * Logs one WARN at startup when the {@code ClientHttpRequestFactoryBuilder} Spring Boot auto-configures for {@code RestClient.Builder} and
 * {@code RestTemplateBuilder} is not the JDK {@code HttpClient} one, whose calls the interceptor's timer is guaranteed to cut off (DL-07). It
 * reads the builder only, without building a factory, so a builder an application customizer wraps can give a false WARN.
 */
final class RequestFactoryCheck {

    /**
     * Checks the builder once.
     *
     * @param builder Boot's auto-configured builder, or {@code null} without Boot's HTTP client auto-configuration, in which case nothing
     *     is logged
     * @param log where the WARN goes
     */
    RequestFactoryCheck(ClientHttpRequestFactoryBuilder<?> builder, Log log) {
        if (builder != null && !(builder instanceof JdkClientHttpRequestFactoryBuilder)) {
            log.warn("Deadline timeout capping is not guaranteed: Spring Boot's auto-configured request factory builder is "
                    + builder.getClass().getName() + ", not the JDK HttpClient one; set spring.http.clients.imperative.factory=jdk to "
                    + "guarantee it. Boundrail-Deadline-Ms and the expiry check before sending still apply.");
        }
    }
}
