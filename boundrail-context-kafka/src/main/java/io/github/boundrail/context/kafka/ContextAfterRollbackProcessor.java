package io.github.boundrail.context.kafka;

import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.springframework.kafka.listener.AfterRollbackProcessor;
import org.springframework.kafka.listener.ContainerProperties.EOSMode;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Decorates a transactional container's {@code AfterRollbackProcessor}: {@code process} runs in the snapshot of the failed record, the first
 * record it is given, as {@link ContextErrorHandler} builds it; every other method is delegated unchanged, unscoped (KAF-03, DESIGN D73,
 * D79).
 */
final class ContextAfterRollbackProcessor<K, V> implements AfterRollbackProcessor<K, V> {

    private final AfterRollbackProcessor<K, V> delegate;
    private final KafkaContext context;

    ContextAfterRollbackProcessor(AfterRollbackProcessor<K, V> delegate, KafkaContext context) {
        this.delegate = delegate;
        this.context = context;
    }

    @Override
    public void process(List<ConsumerRecord<K, V>> records, Consumer<K, V> consumer, MessageListenerContainer container,
            Exception exception, boolean recoverable, EOSMode eosMode) {
        ContextErrorHandler.snapshot(context, records.getFirst())
                .run(() -> delegate.process(records, consumer, container, exception, recoverable, eosMode));
    }

    @Override
    public void processBatch(ConsumerRecords<K, V> records, List<ConsumerRecord<K, V>> recordList, Consumer<K, V> consumer,
            MessageListenerContainer container, Exception exception, boolean recoverable, EOSMode eosMode) {
        delegate.processBatch(records, recordList, consumer, container, exception, recoverable, eosMode);
    }

    @Override
    public void clearThreadState() {
        delegate.clearThreadState();
    }

    @Override
    public boolean isProcessInTransaction() {
        return delegate.isProcessInTransaction();
    }
}
