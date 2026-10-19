package io.github.boundrail.context.kafka;

import io.github.boundrail.context.spring.internal.AdapterSupport;
import org.apache.kafka.clients.producer.Producer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.ProducerPostProcessor;

/** Wraps every producer its factory creates in a {@link ContextProducer} (KAF-01, DESIGN D79). */
final class ContextProducerPostProcessor<K, V> implements ProducerPostProcessor<K, V> {

    private final ObjectProvider<AdapterSupport> support;

    ContextProducerPostProcessor(ObjectProvider<AdapterSupport> support) {
        this.support = support;
    }

    @Override
    public Producer<K, V> apply(Producer<K, V> producer) {
        return new ContextProducer<>(producer, support.getObject());
    }
}
