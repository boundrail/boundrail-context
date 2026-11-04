package io.github.boundrail.context.kafka;

import io.github.boundrail.context.spring.internal.AdapterSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Adds one {@link ContextProducerPostProcessor} to the {@code ProducerFactory} of every {@code KafkaTemplate} bean, subclasses included,
 * unless the factory already holds one, as when two templates share it (KAF-01, DESIGN D71, D79).
 */
final class TemplatePostProcessor implements BeanPostProcessor {

    private final ObjectProvider<AdapterSupport> support;

    TemplatePostProcessor(ObjectProvider<AdapterSupport> support) {
        this.support = support;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof KafkaTemplate<?, ?> template) {
            addTo(template.getProducerFactory());
        }
        return bean;
    }

    private <K, V> void addTo(ProducerFactory<K, V> factory) {
        if (factory.getPostProcessors().stream().noneMatch(ContextProducerPostProcessor.class::isInstance)) {
            factory.addPostProcessor(new ContextProducerPostProcessor<>(support));
        }
    }
}
