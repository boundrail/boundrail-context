package io.github.boundrail.context.kafka.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The broker of 007's tests: one {@code apache/kafka:4.2.1} container per test JVM, started on first use and stopped by Testcontainers
 * when the JVM exits. Every test uses its own topic, and every read its own group id.
 */
public final class KafkaBroker {

    /** Same Kafka version as the kafka-clients managed by the Spring Boot BOM, as in the topology. */
    private static final String IMAGE = "apache/kafka:4.2.1";

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static KafkaContainer container;

    /** {@return the bootstrap servers of the started broker} */
    public static synchronized String bootstrapServers() {
        if (container == null) {
            KafkaContainer started = new KafkaContainer(IMAGE);
            started.start();
            container = started;
        }
        return container.getBootstrapServers();
    }

    /** {@return a topic name no other test uses} */
    public static String topic() {
        return "topic-" + UUID.randomUUID();
    }

    /** {@return the first {@code count} or more records of the topic, read with {@code read_uncommitted}}; fails after a timeout */
    public static List<ConsumerRecord<String, String>> read(String topic, int count) {
        return read(topic, count, "read_uncommitted");
    }

    /** {@return the first {@code count} or more committed records of the topic, read with {@code read_committed}}; fails after a timeout */
    public static List<ConsumerRecord<String, String>> readCommitted(String topic, int count) {
        return read(topic, count, "read_committed");
    }

    private static List<ConsumerRecord<String, String>> read(String topic, int count, String isolation) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.ISOLATION_LEVEL_CONFIG, isolation);
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (records.size() < count && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
        }
        if (records.size() < count) {
            throw new AssertionError("expected at least " + count + " records on " + topic + " within " + TIMEOUT + ", got " + records);
        }
        return records;
    }

    private KafkaBroker() {
    }
}
