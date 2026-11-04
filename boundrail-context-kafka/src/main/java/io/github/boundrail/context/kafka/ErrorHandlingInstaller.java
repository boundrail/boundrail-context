package io.github.boundrail.context.kafka;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.AfterRollbackProcessor;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Before the listener containers start, decorates the error handling of every container of the {@code KafkaListenerEndpointRegistry},
 * {@code MessageListenerContainer} beans included, once: the {@code CommonErrorHandler} of every container, a {@code DefaultErrorHandler}
 * where none is set on a non-transactional one, and also the {@code AfterRollbackProcessor} of a transactional one (KAF-03, DESIGN D73,
 * D79, D82). Registry containers start in the registry's phase, after this; a {@code MessageListenerContainer} bean whose phase is lower
 * than {@code AbstractMessageListenerContainer.DEFAULT_PHASE} can start before this and is not covered. The registry is looked up
 * when this starts: injecting it would make Spring start it, and its containers, first.
 */
final class ErrorHandlingInstaller implements SmartLifecycle {

    private final KafkaContext context;
    private final ObjectProvider<KafkaListenerEndpointRegistry> registry;
    private volatile boolean running;

    ErrorHandlingInstaller(KafkaContext context, ObjectProvider<KafkaListenerEndpointRegistry> registry) {
        this.context = context;
        this.registry = registry;
    }

    @Override
    public void start() {
        registry.ifAvailable(found -> {
            for (MessageListenerContainer container : found.getAllListenerContainers()) {
                if (container instanceof AbstractMessageListenerContainer<?, ?> listenerContainer) {
                    decorate(listenerContainer);
                }
            }
        });
        running = true;
    }

    private <K, V> void decorate(AbstractMessageListenerContainer<K, V> container) {
        boolean transactional = transactional(container);
        CommonErrorHandler handler = container.getCommonErrorHandler();
        if (handler != null && !(handler instanceof ContextErrorHandler)) {
            container.setCommonErrorHandler(new ContextErrorHandler(handler, context));
        } else if (handler == null && !transactional) {
            container.setCommonErrorHandler(new ContextErrorHandler(new DefaultErrorHandler(), context));
        }
        if (transactional) {
            AfterRollbackProcessor<? super K, ? super V> processor = container.getAfterRollbackProcessor();
            if (!(processor instanceof ContextAfterRollbackProcessor)) {
                container.setAfterRollbackProcessor(new ContextAfterRollbackProcessor<>(processor, context));
            }
        }
    }

    /** {@return whether the container has a transaction manager, as the container itself decides} */
    @SuppressWarnings("removal")
    private static boolean transactional(AbstractMessageListenerContainer<?, ?> container) {
        return container.getContainerProperties().getKafkaAwareTransactionManager() != null
                || container.getContainerProperties().getTransactionManager() != null;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return AbstractMessageListenerContainer.DEFAULT_PHASE - 1;
    }
}
