package io.github.boundrail.context.kafka;

import io.github.boundrail.context.spring.BoundrailContextAutoConfiguration;
import io.github.boundrail.context.spring.internal.AdapterSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Spring Boot auto-configuration for the Kafka adapters: active whenever Spring Kafka and the Spring module's auto-configuration are, with no
 * property to turn it off (KAF-04, DESIGN D78, D80).
 */
@AutoConfiguration(after = BoundrailContextAutoConfiguration.class,
        afterName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnBean(AdapterSupport.class)
@EnableConfigurationProperties(BoundrailKafkaProperties.class)
public class BoundrailKafkaAutoConfiguration {

    /** The batch listener helper (KAF-05); not lazy, so {@code kafka.trust-headers} is bound at startup (KAF-04). */
    @Bean
    @Lazy(false)
    KafkaContext boundrailKafkaContext(AdapterSupport support, BoundrailKafkaProperties properties) {
        return new KafkaContext(support, properties.trustHeaders());
    }

    /** Adds the producer hook to the {@code ProducerFactory} of every {@code KafkaTemplate} bean (KAF-01, DESIGN D71, D79). */
    @Bean
    static TemplatePostProcessor boundrailTemplatePostProcessor(ObjectProvider<AdapterSupport> support) {
        return new TemplatePostProcessor(support);
    }
}
