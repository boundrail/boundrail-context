package io.github.boundrail.context.kafka;

import org.aopalliance.aop.Advice;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * Puts a {@link ListenerScopeAdvice} first in the advice chain of every {@code AbstractKafkaListenerContainerFactory} bean, Spring Boot's
 * included, ahead of the advice the application set, unless it already is first; the factory copies the chain into every container it
 * creates (KAF-03, DESIGN D72, D79).
 */
final class ListenerContainerFactoryPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<KafkaContext> context;

    ListenerContainerFactoryPostProcessor(ObjectProvider<KafkaContext> context) {
        this.context = context;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof AbstractKafkaListenerContainerFactory<?, ?, ?> factory) {
            ContainerProperties properties = factory.getContainerProperties();
            Advice[] existing = properties.getAdviceChain();
            if (existing.length == 0 || !(existing[0] instanceof ListenerScopeAdvice)) {
                Advice[] chain = new Advice[existing.length + 1];
                chain[0] = new ListenerScopeAdvice(context.getObject());
                System.arraycopy(existing, 0, chain, 1, existing.length);
                properties.setAdviceChain(chain);
            }
        }
        return bean;
    }
}
