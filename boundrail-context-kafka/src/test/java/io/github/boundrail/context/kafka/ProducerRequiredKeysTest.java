package io.github.boundrail.context.kafka;

import static io.github.boundrail.context.Boundary.INTERNAL_CALL;
import static io.github.boundrail.context.Boundary.MESSAGE;
import static io.github.boundrail.context.kafka.support.TestPolicies.ORDER_COUNT;
import static io.github.boundrail.context.kafka.support.TestPolicies.PRIORITY;
import static io.github.boundrail.context.kafka.support.TestPolicies.TENANT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.ContextViolationException;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.kafka.support.KafkaBroker;
import io.github.boundrail.context.spring.BoundrailContextAutoConfiguration;
import io.github.boundrail.context.spring.ViolationListener;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;

/** The required-key check for {@code MESSAGE} at every send through a producer of a {@code KafkaTemplate} bean's factory (KAF-01, POL-08). */
class ProducerRequiredKeysTest {

    private final List<Violation> received = new CopyOnWriteArrayList<>();

    /**
     * {@return the test policy with {@code tenant} and {@code order-count} required, and {@code priority} required but not propagating to
     * {@code MESSAGE}}
     */
    private static ContextPolicy requiredPolicy() {
        return ContextPolicy.builder()
                .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("Tenant-Id").propagateTo(MESSAGE, INTERNAL_CALL).required())
                .key(ORDER_COUNT, rules -> rules.codec(Codecs.longValue()).wireName("Order-Count").propagateTo(MESSAGE).required())
                .key(PRIORITY, rules -> rules.codec(Codecs.longValue()).wireName("Priority").propagateTo(INTERNAL_CALL).required())
                .build();
    }

    private ApplicationContextRunner runner(String mode) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, BoundrailContextAutoConfiguration.class,
                        BoundrailKafkaAutoConfiguration.class))
                .withPropertyValues("spring.kafka.bootstrap-servers=" + KafkaBroker.bootstrapServers(),
                        "boundrail.context.violations.mode=" + mode)
                .withBean(SimpleMeterRegistry.class)
                .withBean(ViolationListener.class, () -> received::add)
                .withBean(ContextPolicy.class, ProducerRequiredKeysTest::requiredPolicy);
    }

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> template(AssertableApplicationContext context) {
        return context.getBean(KafkaTemplate.class);
    }

    /** {@return the value of the record's only header with that name, decoded as UTF-8, or {@code null} if it has none} */
    private static String header(ConsumerRecord<?, ?> record, String name) {
        List<Header> headers = StreamSupport.stream(record.headers().headers(name).spliterator(), false).toList();
        assertThat(headers).as("headers named %s", name).hasSizeLessThanOrEqualTo(1);
        return headers.isEmpty() ? null : new String(headers.getFirst().value(), UTF_8);
    }

    /** Sends {@code marker} to the topic with every required key bound, and waits for the send. */
    private static void sendMarker(KafkaTemplate<String, String> template, String topic) throws Exception {
        Context.with(TENANT, "acme").with(ORDER_COUNT, 7L).call(() -> template.send(topic, "marker").get());
    }

    @Test
    @DisplayName("KAF-01 in fail mode each unbound required MESSAGE key is reported once as MISSING_REQUIRED in key-name order, then"
            + " ContextViolationException is thrown from the send call and the record never reaches the producer")
    void failsTheSendCall() {
        runner("fail").run(context -> {
            KafkaTemplate<String, String> template = template(context);
            String topic = KafkaBroker.topic();

            Throwable thrown = catchThrowable(() -> template.send(topic, "failed"));
            sendMarker(template, topic);

            assertThat(received).as("DIAG-02: ascending key-name order; priority does not propagate to MESSAGE")
                    .extracting(Violation::kind, Violation::key, Violation::boundary, Violation::destination)
                    .containsExactly(
                            tuple(Violation.Kind.MISSING_REQUIRED, "order-count", MESSAGE, Optional.empty()),
                            tuple(Violation.Kind.MISSING_REQUIRED, "tenant", MESSAGE, Optional.empty()));
            assertThat(thrown).isInstanceOf(ContextViolationException.class);
            assertThat(((ContextViolationException) thrown).violation()).as("the first").isSameAs(received.getFirst());
            assertThat(KafkaBroker.read(topic, 1)).as("only the later record reached the topic").extracting(ConsumerRecord::value)
                    .containsExactly("marker");
        });
    }

    @Test
    @DisplayName("KAF-01 in report mode the record is sent without the key and with the other headers")
    void reportsAndSends() {
        runner("report").run(context -> {
            KafkaTemplate<String, String> template = template(context);
            String topic = KafkaBroker.topic();

            Context.with(ORDER_COUNT, 7L).call(() -> template.send(topic, "v").get());

            ConsumerRecord<String, String> record = KafkaBroker.read(topic, 1).getFirst();
            assertThat(header(record, "Tenant-Id")).isNull();
            assertThat(header(record, "Order-Count")).isEqualTo("7");
            assertThat(received)
                    .extracting(Violation::kind, Violation::key, Violation::boundary, Violation::destination)
                    .containsExactly(tuple(Violation.Kind.MISSING_REQUIRED, "tenant", MESSAGE, Optional.empty()));
        });
    }

    /** A send of one record to a topic through, or next to, the template bean. */
    @FunctionalInterface
    private interface Send {
        Object send(KafkaTemplate<String, String> template, String topic) throws Exception;
    }

    static Stream<Arguments> factoryProducers() {
        return Stream.of(
                Arguments.of("execute(ProducerCallback)", (Send) (template, topic) ->
                        template.execute(producer -> producer.send(new ProducerRecord<>(topic, "failed")))),
                Arguments.of("a template that is not a bean", (Send) (template, topic) ->
                        new KafkaTemplate<>(template.getProducerFactory()).send(topic, "failed")),
                Arguments.of("a template built with config overrides", (Send) (template, topic) -> {
                    KafkaTemplate<String, String> overrides = new KafkaTemplate<>(template.getProducerFactory(),
                            Map.<String, Object>of(ProducerConfig.CLIENT_ID_CONFIG, "overrides"));
                    try {
                        return overrides.send(topic, "failed");
                    } finally {
                        overrides.destroy();
                    }
                }),
                Arguments.of("a producer created directly", (Send) (template, topic) -> {
                    try (Producer<String, String> producer = template.getProducerFactory().createProducer()) {
                        return producer.send(new ProducerRecord<>(topic, "failed"));
                    }
                }));
    }

    @ParameterizedTest(name = "{displayName}: {0}")
    @MethodSource("factoryProducers")
    @DisplayName("KAF-01 in fail mode the check also applies to execute(ProducerCallback), a template that is not a bean, and a producer"
            + " created directly from the factory")
    void failsEveryProducerOfTheFactory(String row, Send send) {
        runner("fail").run(context -> {
            KafkaTemplate<String, String> template = template(context);
            String topic = KafkaBroker.topic();

            Throwable thrown = catchThrowable(() -> send.send(template, topic));
            sendMarker(template, topic);

            assertThat(thrown).isInstanceOf(ContextViolationException.class);
            assertThat(KafkaBroker.read(topic, 1)).as("only the later record reached the topic").extracting(ConsumerRecord::value)
                    .containsExactly("marker");
        });
    }

    @ParameterizedTest(name = "{displayName}: {0} mode")
    @MethodSource("modes")
    @DisplayName("KAF-01 an application-set wire header does not satisfy the required-key check")
    void ignoresApplicationSetHeaders(String mode) {
        runner(mode).run(context -> {
            KafkaTemplate<String, String> template = template(context);
            String topic = KafkaBroker.topic();
            RecordHeaders headers = new RecordHeaders();
            headers.add("Tenant-Id", "app".getBytes(UTF_8));
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, null, (String) null, "app-set", headers);

            Throwable thrown = catchThrowable(() -> Context.with(ORDER_COUNT, 7L).call(() -> template.send(record).get()));

            assertThat(received).as("POL-08 reads the context, not the record's headers")
                    .extracting(Violation::kind, Violation::key)
                    .containsExactly(tuple(Violation.Kind.MISSING_REQUIRED, "tenant"));
            if (mode.equals("fail")) {
                assertThat(thrown).isInstanceOf(ContextViolationException.class);
            } else {
                assertThat(thrown).isNull();
                assertThat(header(KafkaBroker.read(topic, 1).getFirst(), "Tenant-Id")).as("the application's header, unchanged")
                        .isEqualTo("app");
            }
        });
    }

    static Stream<String> modes() {
        return Stream.of("fail", "report");
    }

    @Test
    @DisplayName("KAF-01 inside executeInTransaction the ContextViolationException propagates from the send, and nothing is committed")
    void abortsTheTransaction() {
        runner("fail").withPropertyValues("spring.kafka.producer.transaction-id-prefix=tx-").run(context -> {
            KafkaTemplate<String, String> template = template(context);
            String topic = KafkaBroker.topic();

            Throwable thrown = catchThrowable(() -> template.executeInTransaction(ops -> {
                Context.with(TENANT, "acme").with(ORDER_COUNT, 7L).run(() -> ops.send(topic, "first"));
                return ops.send(topic, "failed");
            }));
            Context.with(TENANT, "acme").with(ORDER_COUNT, 7L)
                    .call(() -> template.executeInTransaction(ops -> ops.send(topic, "marker")).get());

            assertThat(thrown).isInstanceOf(ContextViolationException.class);
            assertThat(KafkaBroker.readCommitted(topic, 1)).as("read_committed: the aborted transaction's first send is not committed")
                    .extracting(ConsumerRecord::value).containsExactly("marker");
        });
    }
}
