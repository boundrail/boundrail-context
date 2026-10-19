package io.github.boundrail.context.kafka;

import static io.github.boundrail.context.kafka.support.TestPolicies.ORDER_COUNT;
import static io.github.boundrail.context.kafka.support.TestPolicies.PRIORITY;
import static io.github.boundrail.context.kafka.support.TestPolicies.TENANT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.boundrail.context.Authority;
import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextCodec;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.Deadline;
import io.github.boundrail.context.Initiator;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.kafka.support.KafkaBroker;
import io.github.boundrail.context.kafka.support.TestPolicies;
import io.github.boundrail.context.spring.BoundrailContextAutoConfiguration;
import io.github.boundrail.context.spring.ViolationListener;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;

/** The {@code MESSAGE} headers written on every send through a producer of a {@code KafkaTemplate} bean's factory (KAF-01). */
class ProducerHeadersTest {

    private final List<Violation> received = new CopyOnWriteArrayList<>();

    private ApplicationContextRunner runner(ContextPolicy policy) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, BoundrailContextAutoConfiguration.class,
                        BoundrailKafkaAutoConfiguration.class))
                .withPropertyValues("spring.kafka.bootstrap-servers=" + KafkaBroker.bootstrapServers())
                .withBean(SimpleMeterRegistry.class)
                .withBean(ViolationListener.class, () -> received::add)
                .withBean(ContextPolicy.class, () -> policy);
    }

    private ApplicationContextRunner runner() {
        return runner(TestPolicies.policy());
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

    /** {@return the names of the record's headers, in order} */
    private static List<String> headerNames(ConsumerRecord<?, ?> record) {
        return StreamSupport.stream(record.headers().spliterator(), false).map(Header::key).toList();
    }

    @Test
    @DisplayName("KAF-01 a record sent through the KafkaTemplate bean carries a header per bound MESSAGE key, holding the UTF-8 bytes"
            + " of its encoding")
    void writesAHeaderPerBoundMessageKey() {
        runner().run(context -> {
            KafkaTemplate<String, String> template = template(context);
            String topic = KafkaBroker.topic();

            Context.with(TENANT, "Zürich").with(ORDER_COUNT, 7L).with(BuiltInKeys.INITIATOR, Initiator.of("user:alice")).with(PRIORITY, 5L)
                    .call(() -> template.send(topic, "first").get());
            Context.with(TENANT, "a\r\nb").call(() -> template.send(topic, "second").get());

            List<ConsumerRecord<String, String>> records = KafkaBroker.read(topic, 2);
            ConsumerRecord<String, String> first = records.get(0);
            assertThat(headerNames(first)).as("no Priority header")
                    .containsExactlyInAnyOrder("Tenant-Id", "Order-Count", "Boundrail-Initiator");
            assertThat(first.headers().lastHeader("Tenant-Id").value()).isEqualTo(new byte[] {
                    'Z', (byte) 0xC3, (byte) 0xBC, 'r', 'i', 'c', 'h'});
            assertThat(header(first, "Order-Count")).isEqualTo("7");
            assertThat(header(first, "Boundrail-Initiator")).isEqualTo("user:alice");
            assertThat(header(records.get(1), "Tenant-Id")).as("CR and LF written as is (DESIGN D74)").isEqualTo("a\r\nb");
            assertThat(received).isEmpty();
        });
    }

    @Test
    @DisplayName("KAF-01 no Authorization or Boundrail-Deadline-Ms header is written, and an expired deadline neither fails nor changes the"
            + " send")
    void writesNoAuthorityOrDeadline() {
        runner().run(context -> {
            KafkaTemplate<String, String> template = template(context);
            String topic = KafkaBroker.topic();

            Context.with(TENANT, "acme").with(BuiltInKeys.AUTHORITY, Authority.of("alice", "alice-token"))
                    .with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ofMinutes(5)))
                    .call(() -> template.send(topic, "live").get());
            Context.with(TENANT, "acme").with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ZERO))
                    .call(() -> template.send(topic, "expired").get());

            List<ConsumerRecord<String, String>> records = KafkaBroker.read(topic, 2);
            assertThat(headerNames(records.get(0))).as("ID-06").containsExactly("Tenant-Id");
            assertThat(records.get(1).value()).isEqualTo("expired");
            assertThat(headerNames(records.get(1))).as("DESIGN D75").containsExactly("Tenant-Id");
            assertThat(header(records.get(1), "Tenant-Id")).isEqualTo("acme");
            assertThat(received).isEmpty();
        });
    }

    /** A send through a template to a topic; returns what the send method returns. */
    @FunctionalInterface
    private interface Send {
        Object send(KafkaTemplate<String, String> template, String topic) throws Exception;
    }

    static Stream<Arguments> sendMethods() {
        List<Arguments> rows = new ArrayList<>();
        Map<String, Send> methods = new LinkedHashMap<>();
        methods.put("send(topic, value)", (template, topic) -> template.send(topic, "v").get());
        methods.put("send(topic, key, value)", (template, topic) -> template.send(topic, "k", "v").get());
        methods.put("send(topic, partition, key, value)", (template, topic) -> template.send(topic, 0, "k", "v").get());
        methods.put("send(topic, partition, timestamp, key, value)",
                (template, topic) -> template.send(topic, 0, System.currentTimeMillis(), "k", "v").get());
        methods.put("send(ProducerRecord)", (template, topic) -> template.send(new ProducerRecord<>(topic, "v")).get());
        methods.put("send(Message)",
                (template, topic) -> template.send(MessageBuilder.withPayload("v").setHeader(KafkaHeaders.TOPIC, topic).build()).get());
        methods.put("sendDefault(value)", (template, topic) -> template.sendDefault("v").get());
        methods.put("sendDefault(key, value)", (template, topic) -> template.sendDefault("k", "v").get());
        for (boolean observation : new boolean[] {false, true}) {
            methods.forEach((name, send) -> rows.add(Arguments.of(name, send, false, observation)));
            rows.add(Arguments.of("executeInTransaction(ops -> ops.send(topic, value))",
                    (Send) (template, topic) -> template.executeInTransaction(ops -> ops.send(topic, "v")).get(), true, observation));
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "{0}, transactional {2}, observation {3}")
    @MethodSource("sendMethods")
    @DisplayName("KAF-01 every send method of a KafkaTemplate bean is covered, including Message sends and sends inside"
            + " executeInTransaction, with template observation off and on")
    void coversEverySendMethod(String method, Send send, boolean transactional, boolean observation) {
        String topic = KafkaBroker.topic();
        AtomicInteger observations = new AtomicInteger();
        List<String> properties = new ArrayList<>(List.of(
                "spring.kafka.template.default-topic=" + topic,
                "spring.kafka.template.observation-enabled=" + observation));
        if (transactional) {
            properties.add("spring.kafka.producer.transaction-id-prefix=tx-");
        }
        runner()
                .withPropertyValues(properties.toArray(String[]::new))
                .withBean(ObservationRegistry.class, () -> {
                    ObservationRegistry registry = ObservationRegistry.create();
                    registry.observationConfig().observationHandler(observed -> observations.incrementAndGet() >= 0);
                    return registry;
                })
                .run(context -> {
                    KafkaTemplate<String, String> template = template(context);

                    Context.with(TENANT, "acme").call(() -> send.send(template, topic));

                    List<ConsumerRecord<String, String>> records =
                            transactional ? KafkaBroker.readCommitted(topic, 1) : KafkaBroker.read(topic, 1);
                    assertThat(header(records.getFirst(), "Tenant-Id")).isEqualTo("acme");
                    assertThat(observations.get() > 0).as("the row's template observation setting is in effect").isEqualTo(observation);
                });
    }

    static Stream<Arguments> factoryProducers() {
        return Stream.of(
                Arguments.of("execute(ProducerCallback)", (Send) (template, topic) ->
                        template.execute(producer -> producer.send(new ProducerRecord<>(topic, "v"))).get()),
                Arguments.of("a template that is not a bean", (Send) (template, topic) ->
                        new KafkaTemplate<>(template.getProducerFactory()).send(topic, "v").get()),
                Arguments.of("a template built with config overrides", (Send) (template, topic) -> {
                    KafkaTemplate<String, String> overrides = new KafkaTemplate<>(template.getProducerFactory(),
                            Map.<String, Object>of(ProducerConfig.CLIENT_ID_CONFIG, "overrides"));
                    try {
                        return overrides.send(topic, "v").get();
                    } finally {
                        overrides.destroy();
                    }
                }),
                Arguments.of("a producer created directly", (Send) (template, topic) -> {
                    try (Producer<String, String> producer = template.getProducerFactory().createProducer()) {
                        RecordMetadata metadata = producer.send(new ProducerRecord<>(topic, "v")).get();
                        return metadata;
                    }
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factoryProducers")
    @DisplayName("KAF-01 every producer the template bean's ProducerFactory creates is covered: execute(ProducerCallback), a template that"
            + " is not a bean, and a producer created directly")
    void coversEveryProducerOfTheFactory(String row, Send send) {
        runner().run(context -> {
            KafkaTemplate<String, String> template = template(context);
            String topic = KafkaBroker.topic();

            Context.with(TENANT, "acme").call(() -> send.send(template, topic));

            assertThat(header(KafkaBroker.read(topic, 1).getFirst(), "Tenant-Id")).isEqualTo("acme");
        });
    }

    /** A {@code KafkaTemplate} subclass, as an application might declare. */
    static class AppTemplate extends KafkaTemplate<String, String> {

        AppTemplate(ProducerFactory<String, String> factory) {
            super(factory);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SubclassTemplate {

        @Bean
        AppTemplate appTemplate(ProducerFactory<String, String> factory) {
            return new AppTemplate(factory);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SecondTemplate {

        @Bean
        KafkaTemplate<String, String> secondTemplate(ProducerFactory<String, String> factory) {
            return new KafkaTemplate<>(factory);
        }
    }

    @Test
    @DisplayName("KAF-01 a KafkaTemplate subclass bean, and a second template bean sharing the producer factory, are covered, and the"
            + " factory gets one post-processor")
    void coversSubclassAndSharedFactory() {
        runner().withUserConfiguration(SubclassTemplate.class).run(context -> {
            String topic = KafkaBroker.topic();

            Context.with(TENANT, "acme").call(() -> context.getBean(AppTemplate.class).send(topic, "subclass").get());

            assertThat(header(KafkaBroker.read(topic, 1).getFirst(), "Tenant-Id")).as("the subclass alone").isEqualTo("acme");
        });
        runner().withUserConfiguration(SubclassTemplate.class, SecondTemplate.class).run(context -> {
            AppTemplate subclass = context.getBean(AppTemplate.class);
            @SuppressWarnings("unchecked")
            KafkaTemplate<String, String> second = context.getBean("secondTemplate", KafkaTemplate.class);
            String topic = KafkaBroker.topic();

            Context.with(TENANT, "acme").run(() -> {
                subclass.send(topic, "subclass").join();
                second.send(topic, "second").join();
            });

            List<ConsumerRecord<String, String>> records = KafkaBroker.read(topic, 2);
            assertThat(records).extracting(record -> header(record, "Tenant-Id")).containsExactly("acme", "acme");
            assertThat(subclass.getProducerFactory()).isSameAs(second.getProducerFactory());
            assertThat(subclass.getProducerFactory().getPostProcessors()).hasSize(1);
        });
    }

    /** An application's own template interceptor: adds {@code X-App} and records whether each record already had {@code Tenant-Id}. */
    static class RecordingInterceptor implements ProducerInterceptor<String, String> {

        final List<Boolean> sawTenantId = new CopyOnWriteArrayList<>();

        @Override
        public ProducerRecord<String, String> onSend(ProducerRecord<String, String> record) {
            sawTenantId.add(record.headers().lastHeader("Tenant-Id") != null);
            record.headers().add("X-App", "app".getBytes(UTF_8));
            return record;
        }

        @Override
        public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        }

        @Override
        public void close() {
        }

        @Override
        public void configure(Map<String, ?> configs) {
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class InterceptedTemplate {

        @Bean
        RecordingInterceptor recordingInterceptor() {
            return new RecordingInterceptor();
        }

        @Bean
        KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> factory, RecordingInterceptor interceptor) {
            KafkaTemplate<String, String> template = new KafkaTemplate<>(factory);
            template.setProducerInterceptor(interceptor);
            return template;
        }
    }

    @Test
    @DisplayName("KAF-01 an application's own template interceptor runs first, and the record still carries the headers")
    void composesWithTheApplicationsInterceptor() {
        runner().withUserConfiguration(InterceptedTemplate.class).run(context -> {
            KafkaTemplate<String, String> template = template(context);
            String topic = KafkaBroker.topic();

            Context.with(TENANT, "acme").call(() -> template.send(topic, "v").get());

            ConsumerRecord<String, String> record = KafkaBroker.read(topic, 1).getFirst();
            assertThat(context.getBean(RecordingInterceptor.class).sawTenantId).as("called once, before Tenant-Id is written")
                    .containsExactly(false);
            assertThat(header(record, "X-App")).isEqualTo("app");
            assertThat(header(record, "Tenant-Id")).isEqualTo("acme");
        });
    }

    @Test
    @DisplayName("KAF-01 headers come from the sending thread's context at the send call")
    void readsTheSendingThreadsContext() {
        runner().run(context -> {
            KafkaTemplate<String, String> template = template(context);
            String topic = KafkaBroker.topic();

            Context.with(TENANT, "a").call(() -> template.send(topic, "inside").get());
            template.send(topic, "outside").get();

            List<ConsumerRecord<String, String>> records = KafkaBroker.read(topic, 2);
            assertThat(records).extracting(ConsumerRecord::value).containsExactly("inside", "outside");
            assertThat(header(records.get(0), "Tenant-Id")).isEqualTo("a");
            assertThat(header(records.get(1), "Tenant-Id")).isNull();
        });
    }

    @Test
    @DisplayName("KAF-01 an exception from encode propagates unchanged from the send call, nothing is reported, and the record is not sent")
    void propagatesEncodeExceptions() {
        IllegalStateException boom = new IllegalStateException("boom");
        ContextCodec<String> throwing = new ContextCodec<>() {
            @Override
            public String encode(String value) {
                throw boom;
            }

            @Override
            public String decode(String text) {
                return Codecs.string().decode(text);
            }
        };
        runner(TestPolicies.withTenantCodec(throwing)).run(context -> {
            KafkaTemplate<String, String> template = template(context);
            String topic = KafkaBroker.topic();

            Throwable thrown = catchThrowable(() -> Context.with(TENANT, "acme").call(() -> template.send(topic, "failed")));
            template.send(topic, "marker").get();

            assertThat(thrown).as("WIRE-05").isSameAs(boom);
            assertThat(received).isEmpty();
            assertThat(KafkaBroker.read(topic, 1)).as("only the later record reached the topic").extracting(ConsumerRecord::value)
                    .containsExactly("marker");
        });
    }
}
