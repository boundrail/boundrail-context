package io.github.boundrail.context.spring;

import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.ContextPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Locale;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.RestTemplateCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.client.ClientHttpRequestInterceptor;

/** Spring Boot auto-configuration for Boundrail Context. */
@AutoConfiguration
@EnableConfigurationProperties(BoundrailContextProperties.class)
public class BoundrailContextAutoConfiguration {

    /** The built-in policy merged, in bean order, with every {@code ContextPolicy} bean; a conflict fails startup (POL-07, POL-05). */
    @Bean
    @Lazy(false)
    EffectivePolicy boundrailEffectivePolicy(ObjectProvider<ContextPolicy> policies) {
        ContextPolicy effective = BuiltInKeys.defaultPolicy();
        for (ContextPolicy policy : policies.orderedStream().toList()) {
            effective = effective.merge(policy);
        }
        return new EffectivePolicy(effective);
    }

    /** Logs violations and, with Micrometer, counts them (DIAG-03). */
    @Bean
    ViolationReporter boundrailViolationReporter(EffectivePolicy policy, BoundrailContextProperties properties,
            ObjectProvider<ViolationReporter.Counter> counter) {
        return new ViolationReporter(policy, properties.violations().logInterval(), counter.getIfAvailable(() -> violation -> { }),
                LogFactory.getLog(ViolationReporter.class), Clock.systemUTC());
    }

    /** Counts violations in the application's unique or primary {@code MeterRegistry} bean, never the global registry (DIAG-03). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MicrometerConfiguration {

        @Bean
        ViolationReporter.Counter boundrailViolationCounter(ObjectProvider<MeterRegistry> registries) {
            return violation -> {
                MeterRegistry registry = registries.getIfUnique();
                if (registry != null) {
                    registry.counter("boundrail.context.violations",
                            "kind", violation.kind().name().toLowerCase(Locale.ROOT),
                            "key", violation.key().toLowerCase(Locale.ROOT),
                            "boundary", violation.boundary().name().toLowerCase(Locale.ROOT)).increment();
                }
            };
        }
    }

    /** Adds the library's interceptor to every auto-configured {@code RestClient.Builder} and {@code RestTemplateBuilder} (OUT-01). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({ClientHttpRequestInterceptor.class, RestClientCustomizer.class, RestTemplateCustomizer.class})
    static class ClientConfiguration {

        @Bean
        RestClientCustomizer boundrailRestClientCustomizer() {
            return builder -> builder.requestInterceptor(new ContextClientInterceptor());
        }

        @Bean
        RestTemplateCustomizer boundrailRestTemplateCustomizer() {
            return restTemplate -> restTemplate.getInterceptors().add(new ContextClientInterceptor());
        }
    }
}
