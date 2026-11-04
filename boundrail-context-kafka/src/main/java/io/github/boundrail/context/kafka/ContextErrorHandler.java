package io.github.boundrail.context.kafka;

import io.github.boundrail.context.ContextSnapshot;
import java.util.Collection;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.springframework.core.log.LogAccessor;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.TopicPartitionOffset;

/**
 * Decorates a container's {@code CommonErrorHandler}: {@code handleOne} and {@code handleRemaining} run in the snapshot of the failed record,
 * the first record they are given, rebuilt without reporting, or in the empty snapshot if rebuilding throws a {@link RuntimeException}
 * (an {@link Error} propagates); every other method is delegated unchanged, unscoped (KAF-03, DESIGN D73, D79, D82).
 */
final class ContextErrorHandler implements CommonErrorHandler {

    private final CommonErrorHandler delegate;
    private final KafkaContext context;

    ContextErrorHandler(CommonErrorHandler delegate, KafkaContext context) {
        this.delegate = delegate;
        this.context = context;
    }

    /**
     * {@return the record's snapshot rebuilt without reporting, or the empty snapshot if rebuilding throws a {@link RuntimeException}
     * (KAF-03, DESIGN D79, D82)}
     */
    static ContextSnapshot snapshot(KafkaContext context, ConsumerRecord<?, ?> record) {
        try {
            return context.rebuild(record);
        } catch (RuntimeException e) {
            return ContextSnapshot.EMPTY;
        }
    }

    @Override
    public boolean handleOne(Exception thrownException, ConsumerRecord<?, ?> record, Consumer<?, ?> consumer,
            MessageListenerContainer container) {
        return snapshot(context, record).call(() -> delegate.handleOne(thrownException, record, consumer, container));
    }

    @Override
    public void handleRemaining(Exception thrownException, List<ConsumerRecord<?, ?>> records, Consumer<?, ?> consumer,
            MessageListenerContainer container) {
        snapshot(context, records.getFirst()).run(() -> delegate.handleRemaining(thrownException, records, consumer, container));
    }

    @Override
    public boolean seeksAfterHandling() {
        return delegate.seeksAfterHandling();
    }

    @Override
    public boolean deliveryAttemptHeader() {
        return delegate.deliveryAttemptHeader();
    }

    @Override
    public void handleOtherException(Exception thrownException, Consumer<?, ?> consumer, MessageListenerContainer container,
            boolean batchListener) {
        delegate.handleOtherException(thrownException, consumer, container, batchListener);
    }

    @Override
    public void handleBatch(Exception thrownException, ConsumerRecords<?, ?> data, Consumer<?, ?> consumer,
            MessageListenerContainer container, Runnable invokeListener) {
        delegate.handleBatch(thrownException, data, consumer, container, invokeListener);
    }

    @Override
    public LogAccessor logger() {
        return delegate.logger();
    }

    @Override
    public <K, V> ConsumerRecords<K, V> handleBatchAndReturnRemaining(Exception thrownException, ConsumerRecords<?, ?> data,
            Consumer<?, ?> consumer, MessageListenerContainer container, Runnable invokeListener) {
        return delegate.handleBatchAndReturnRemaining(thrownException, data, consumer, container, invokeListener);
    }

    @Override
    public int deliveryAttempt(TopicPartitionOffset topicPartitionOffset) {
        return delegate.deliveryAttempt(topicPartitionOffset);
    }

    @Override
    public void clearThreadState() {
        delegate.clearThreadState();
    }

    @Override
    public boolean isAckAfterHandle() {
        return delegate.isAckAfterHandle();
    }

    @Override
    public void setAckAfterHandle(boolean ack) {
        delegate.setAckAfterHandle(ack);
    }

    @Override
    public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions, Runnable publishPause) {
        delegate.onPartitionsAssigned(consumer, partitions, publishPause);
    }
}
