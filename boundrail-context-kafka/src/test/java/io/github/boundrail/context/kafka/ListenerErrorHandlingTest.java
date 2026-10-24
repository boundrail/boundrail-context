package io.github.boundrail.context.kafka;

import static io.github.boundrail.context.Boundary.MESSAGE;
import static io.github.boundrail.context.kafka.support.TestPolicies.ORDER_COUNT;
import static io.github.boundrail.context.kafka.support.TestPolicies.TENANT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextCodec;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.ContextSnapshot;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.kafka.support.KafkaBroker;
import io.github.boundrail.context.spring.BoundrailContextAutoConfiguration;
import io.github.boundrail.context.spring.ViolationListener;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.AfterRollbackProcessor;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultAfterRollbackProcessor;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.util.backoff.FixedBackOff;

/**
 * The container's error handling in the failed record's snapshot, against the broker (KAF-03, DESIGN D73, D79): {@code tenant} is required
 * for {@code MESSAGE} and {@code violations.mode=fail}, so a dead-letter send outside the record's snapshot fails. Records are produced by a
 * plain {@code KafkaProducer} with exact headers.
 */
@ExtendWith(OutputCaptureExtension.class)
class ListenerErrorHandlingTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** The runner's properties that make the record's value fail deserialization when it is {@code poison}. */
    private static final String[] POISON_VALUES = {
            "spring.kafka.consumer.value-deserializer=" + ErrorHandlingDeserializer.class.getName(),
            "spring.kafka.consumer.properties.spring.deserializer.value.delegate.class=" + PoisonDeserializer.class.getName()};

    private final List<Violation> received = new CopyOnWriteArrayList<>();

    /** The {@code Error} the {@code order-count} codec throws for {@code error}. */
    static final class CodecError extends AssertionError {

        CodecError() {
            super("order-count codec error");
        }
    }

    /** {@return the test policy with {@code tenant} required and an {@code order-count} codec that throws
     * {@code IllegalStateException} for {@code explode} and {@link CodecError} for {@code error}} */
    private static ContextPolicy policy() {
        ContextCodec<Long> orderCount = new ContextCodec<>() {
            @Override
            public String encode(Long value) {
                return Codecs.longValue().encode(value);
            }

            @Override
            public Long decode(String text) {
                if (text.equals("explode")) {
                    throw new IllegalStateException("order-count codec failure");
                }
                if (text.equals("error")) {
                    throw new CodecError();
                }
                return Codecs.longValue().decode(text);
            }
        };
        return ContextPolicy.builder()
                .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("Tenant-Id").propagateTo(MESSAGE).required())
                .key(ORDER_COUNT, rules -> rules.codec(orderCount).wireName("Order-Count").propagateTo(MESSAGE))
                .build();
    }

    /** The runner on the broker, with {@code test.topic} set to a new topic, which listeners also use as their group id. */
    private ApplicationContextRunner runner(String topic) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, BoundrailContextAutoConfiguration.class,
                        BoundrailKafkaAutoConfiguration.class))
                .withPropertyValues("spring.kafka.bootstrap-servers=" + KafkaBroker.bootstrapServers(),
                        "spring.kafka.consumer.auto-offset-reset=earliest",
                        "boundrail.context.violations.mode=fail",
                        "test.topic=" + topic)
                .withBean(SimpleMeterRegistry.class)
                .withBean(ViolationListener.class, () -> received::add)
                .withBean(ContextPolicy.class, ListenerErrorHandlingTest::policy)
                .withUserConfiguration(Common.class);
    }

    /** Produces a record with the value and the headers given as name and value pairs, in order, through a plain producer. */
    private static void produce(String topic, String value, String... namesAndValues) throws Exception {
        RecordHeaders headers = new RecordHeaders();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            headers.add(namesAndValues[i], namesAndValues[i + 1].getBytes(UTF_8));
        }
        Map<String, Object> config = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaBroker.bootstrapServers());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<String, String>(topic, null, null, value, headers)).get();
        }
    }

    /** {@return the values of the record's headers with that name, decoded as UTF-8, in order} */
    private static List<String> headers(ConsumerRecord<?, ?> record, String name) {
        return StreamSupport.stream(record.headers().headers(name).spliterator(), false)
                .map(header -> new String(header.value(), UTF_8))
                .toList();
    }

    private List<Violation> received(Violation.Kind kind) {
        return received.stream().filter(violation -> violation.kind() == kind).toList();
    }

    /** What the listeners, the application's error handler and the record interceptor observed, each with {@code Context.capture()}. */
    static final class Recorder {

        final List<ContextSnapshot> listener = new CopyOnWriteArrayList<>();
        final List<Map.Entry<String, ContextSnapshot>> handled = new CopyOnWriteArrayList<>();
        final AtomicInteger deliveries = new AtomicInteger();
        final List<ContextSnapshot> afterRecord = new CopyOnWriteArrayList<>();

        /** An application {@code RecordInterceptor}: counts deliveries, and records the snapshot of each {@code afterRecord}. */
        final RecordInterceptor<Object, Object> recordInterceptor = new RecordInterceptor<>() {
            @Override
            public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
                deliveries.incrementAndGet();
                return record;
            }

            @Override
            public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
                afterRecord.add(Context.capture());
            }
        };

        /** The record listener: records the snapshot, then throws. */
        void onRecord(ConsumerRecord<?, ?> record) {
            listener.add(Context.capture());
            throw new IllegalStateException("listener failure");
        }
    }

    /** An application error handler, {@code DefaultErrorHandler} without retries; records the snapshot of each handling call. */
    static final class RecordingErrorHandler extends DefaultErrorHandler {

        private final List<Map.Entry<String, ContextSnapshot>> handled;

        RecordingErrorHandler(List<Map.Entry<String, ContextSnapshot>> handled, ConsumerRecordRecoverer recoverer) {
            super(recoverer, new FixedBackOff(0L, 0L));
            this.handled = handled;
        }

        @Override
        public boolean handleOne(Exception thrownException, ConsumerRecord<?, ?> record, Consumer<?, ?> consumer,
                MessageListenerContainer container) {
            handled.add(Map.entry("handleOne", Context.capture()));
            return super.handleOne(thrownException, record, consumer, container);
        }

        @Override
        public void handleRemaining(Exception thrownException, List<ConsumerRecord<?, ?>> records, Consumer<?, ?> consumer,
                MessageListenerContainer container) {
            handled.add(Map.entry("handleRemaining", Context.capture()));
            super.handleRemaining(thrownException, records, consumer, container);
        }
    }

    /** A deserializer that throws for the value {@code poison}. */
    public static final class PoisonDeserializer implements Deserializer<String> {

        @Override
        public String deserialize(String topic, byte[] data) {
            String value = new String(data, UTF_8);
            if (value.equals("poison")) {
                throw new IllegalStateException("poison");
            }
            return value;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Common {

        @Bean
        Recorder recorder() {
            return new Recorder();
        }

        /** Picked up by Boot's factory. */
        @Bean
        RecordInterceptor<Object, Object> recordInterceptor(Recorder recorder) {
            return recorder.recordInterceptor;
        }
    }

    /** {@return the application's recording handler, which seeks after an error unless {@code test.seek-after-error} is {@code false}} */
    private static CommonErrorHandler applicationHandler(Recorder recorder, boolean seekAfterError) {
        RecordingErrorHandler handler = new RecordingErrorHandler(recorder.handled, null);
        handler.setSeekAfterError(seekAfterError);
        return handler;
    }

    /** The application's recording handler as a {@code CommonErrorHandler} bean, which Boot's factory picks up. */
    @Configuration(proxyBeanMethods = false)
    static class BootHandler {

        @Bean
        CommonErrorHandler applicationHandler(Recorder recorder, @Value("${test.seek-after-error}") boolean seekAfterError) {
            return ListenerErrorHandlingTest.applicationHandler(recorder, seekAfterError);
        }
    }

    /** An application factory bean whose {@code @Bean} method sets the application's recording handler and its record interceptor. */
    @Configuration(proxyBeanMethods = false)
    static class ApplicationFactory {

        @Bean
        ConcurrentKafkaListenerContainerFactory<Object, Object> appFactory(ConsumerFactory<Object, Object> consumerFactory,
                Recorder recorder, @Value("${test.seek-after-error}") boolean seekAfterError) {
            ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(consumerFactory);
            factory.setRecordInterceptor(recorder.recordInterceptor);
            factory.setCommonErrorHandler(ListenerErrorHandlingTest.applicationHandler(recorder, seekAfterError));
            return factory;
        }
    }

    /** A {@code @KafkaListener} on Boot's factory. */
    static class BootFactoryListener {

        private final Recorder recorder;

        BootFactoryListener(Recorder recorder) {
            this.recorder = recorder;
        }

        @KafkaListener(topics = "${test.topic}", groupId = "${test.topic}")
        void onRecord(ConsumerRecord<String, String> record) {
            recorder.onRecord(record);
        }
    }

    /** A {@code @KafkaListener} on the application factory. */
    static class ApplicationFactoryListener {

        private final Recorder recorder;

        ApplicationFactoryListener(Recorder recorder) {
            this.recorder = recorder;
        }

        @KafkaListener(topics = "${test.topic}", groupId = "${test.topic}", containerFactory = "appFactory")
        void onRecord(ConsumerRecord<String, String> record) {
            recorder.onRecord(record);
        }
    }

    /** A container bean created with the application factory's {@code createContainer}. */
    @Configuration(proxyBeanMethods = false)
    static class CreatedContainer {

        @Bean
        ConcurrentMessageListenerContainer<Object, Object> createdContainer(
                @Qualifier("appFactory") ConcurrentKafkaListenerContainerFactory<Object, Object> appFactory, Recorder recorder,
                @Value("${test.topic}") String topic) {
            ConcurrentMessageListenerContainer<Object, Object> container = appFactory.createContainer(topic);
            container.getContainerProperties().setGroupId(topic);
            container.getContainerProperties().setMessageListener((MessageListener<Object, Object>) recorder::onRecord);
            return container;
        }
    }

    @ParameterizedTest(name = "{displayName}: {0}")
    @MethodSource("handlingCases")
    @DisplayName("KAF-03 a CommonErrorHandler handling a record whose listener threw runs in that record's snapshot, and the previous"
            + " snapshot is restored afterwards")
    void errorHandlerRunsInTheRecordsSnapshot(String row, boolean seekAfterError, String method, Class<?>[] configurations) {
        String topic = KafkaBroker.topic();
        runner(topic).withPropertyValues("test.seek-after-error=" + seekAfterError).withUserConfiguration(configurations).run(context -> {
            Recorder recorder = context.getBean(Recorder.class);

            produce(topic, "boom", "Tenant-Id", "acme");

            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(recorder.afterRecord).hasSize(1));
            assertThat(recorder.listener).as("the listener").singleElement()
                    .satisfies(seen -> assertThat(seen.find(TENANT)).contains("acme"));
            assertThat(recorder.handled).as("the application's handler, once per failure").extracting(Map.Entry::getKey)
                    .containsExactly(method);
            assertThat(recorder.handled.getFirst().getValue().find(TENANT)).as("the application's handler sees the record's snapshot")
                    .contains("acme");
            assertThat(recorder.afterRecord).as("RecordInterceptor.afterRecord that follows sees no binding")
                    .singleElement().satisfies(seen -> assertThat(seen.isEmpty()).isTrue());
        });
    }

    static Stream<Arguments> handlingCases() {
        Class<?>[] bootFactory = {BootHandler.class, BootFactoryListener.class};
        Class<?>[] appFactory = {ApplicationFactory.class, ApplicationFactoryListener.class};
        Class<?>[] createdContainer = {ApplicationFactory.class, CreatedContainer.class};
        return Stream.of(
                Arguments.of("handleRemaining, CommonErrorHandler bean on Boot's factory, @KafkaListener", true, "handleRemaining",
                        bootFactory),
                Arguments.of("handleOne, CommonErrorHandler bean on Boot's factory, @KafkaListener", false, "handleOne", bootFactory),
                Arguments.of("handleRemaining, handler set on an application factory, @KafkaListener", true, "handleRemaining",
                        appFactory),
                Arguments.of("handleOne, handler set on an application factory, @KafkaListener", false, "handleOne", appFactory),
                Arguments.of("handleRemaining, handler set on an application factory, createContainer bean", true, "handleRemaining",
                        createdContainer),
                Arguments.of("handleOne, handler set on an application factory, createContainer bean", false, "handleOne",
                        createdContainer));
    }

    /** A {@code DefaultErrorHandler} bean, without retries, that dead-letters through the template bean. */
    @Configuration(proxyBeanMethods = false)
    static class DeadLetterHandler {

        @Bean
        CommonErrorHandler deadLetterHandler(KafkaTemplate<Object, Object> template) {
            return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), new FixedBackOff(0L, 0L));
        }
    }

    @Test
    @DisplayName("KAF-03 a DeadLetterPublishingRecoverer publishes the failed record in fail mode with a required MESSAGE key, and its"
            + " violations are reported once")
    void deadLetterRecovererPublishesInTheRecordsSnapshot() {
        String topic = KafkaBroker.topic();
        runner(topic).withUserConfiguration(DeadLetterHandler.class, BootFactoryListener.class).run(context -> {
            produce(topic, "boom", "Tenant-Id", "acme", "Order-Count", "1", "Order-Count", "2");

            ConsumerRecord<String, String> deadLetter = KafkaBroker.read(topic + "-dlt", 1).getFirst();

            assertThat(deadLetter.value()).isEqualTo("boom");
            assertThat(headers(deadLetter, "Tenant-Id")).as("copied by the recoverer").containsExactly("acme");
            assertThat(received(Violation.Kind.MISSING_REQUIRED)).as("no MISSING_REQUIRED").isEmpty();
            assertThat(received(Violation.Kind.INVALID_VALUE)).as("one INVALID_VALUE, from the delivery, none from the rebuild")
                    .singleElement().satisfies(violation -> assertThat(violation.key()).isEqualTo("order-count"));
        });
    }

    @Test
    @DisplayName("KAF-03 a non-transactional container without an error handler gets a decorated DefaultErrorHandler, and a"
            + " transactional container gets none")
    void defaultErrorHandlerOnlyOnNonTransactionalContainers(CapturedOutput output) {
        String topic = KafkaBroker.topic();
        runner(topic).withUserConfiguration(BootFactoryListener.class).run(context -> {
            Recorder recorder = context.getBean(Recorder.class);

            produce(topic, "boom", "Tenant-Id", "acme");

            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(output).contains("exhausted for " + topic + "-0@0"));
            assertThat(recorder.listener).as("retried as DefaultErrorHandler does: 1 delivery and 9 retries").hasSize(10);
            assertThat(container(context.getBean(KafkaListenerEndpointRegistry.class)).getCommonErrorHandler())
                    .isInstanceOf(ContextErrorHandler.class);
        });

        String transactionalTopic = KafkaBroker.topic();
        runner(transactionalTopic).withPropertyValues("spring.kafka.producer.transaction-id-prefix=tx-")
                .withUserConfiguration(BootFactoryListener.class).run(context -> {
                    AbstractMessageListenerContainer<?, ?> container = container(context.getBean(KafkaListenerEndpointRegistry.class));

                    assertThat(container.isRunning()).as("started").isTrue();
                    assertThat(container.getCommonErrorHandler()).as("the transactional container's handler").isNull();
                });
    }

    /** {@return the registry's only listener container} */
    private static AbstractMessageListenerContainer<?, ?> container(KafkaListenerEndpointRegistry registry) {
        assertThat(registry.getListenerContainers()).hasSize(1);
        return (AbstractMessageListenerContainer<?, ?>) registry.getListenerContainers().iterator().next();
    }

    /** A {@code DefaultAfterRollbackProcessor} bean, without retries, that dead-letters through the template bean in a transaction. */
    @Configuration(proxyBeanMethods = false)
    static class DeadLetterAfterRollback {

        @Bean
        AfterRollbackProcessor<Object, Object> deadLetterAfterRollback(KafkaTemplate<Object, Object> template) {
            return new DefaultAfterRollbackProcessor<>(new DeadLetterPublishingRecoverer(template), new FixedBackOff(0L, 0L), template,
                    true);
        }
    }

    @Test
    @DisplayName("KAF-03 an AfterRollbackProcessor of a transactional container processes the failed record in its snapshot")
    void afterRollbackProcessorRunsInTheRecordsSnapshot() {
        String topic = KafkaBroker.topic();
        runner(topic).withPropertyValues("spring.kafka.producer.transaction-id-prefix=tx-")
                .withUserConfiguration(DeadLetterAfterRollback.class, BootFactoryListener.class).run(context -> {
                    produce(topic, "boom", "Tenant-Id", "acme");

                    ConsumerRecord<String, String> deadLetter = KafkaBroker.readCommitted(topic + "-dlt", 1).getFirst();

                    assertThat(deadLetter.value()).isEqualTo("boom");
                    assertThat(headers(deadLetter, "Tenant-Id")).as("copied by the recoverer").containsExactly("acme");
                    assertThat(received(Violation.Kind.MISSING_REQUIRED)).as("no MISSING_REQUIRED").isEmpty();
                });
    }

    @Test
    @DisplayName("KAF-03 a CommonErrorHandler of a transactional container, which runs inside the transaction, handles the failed record"
            + " in its snapshot")
    void transactionalContainersErrorHandlerRunsInTheRecordsSnapshot() {
        String topic = KafkaBroker.topic();
        runner(topic).withPropertyValues("spring.kafka.producer.transaction-id-prefix=tx-")
                .withUserConfiguration(DeadLetterHandler.class, BootFactoryListener.class).run(context -> {
                    produce(topic, "boom", "Tenant-Id", "acme");

                    ConsumerRecord<String, String> deadLetter = KafkaBroker.readCommitted(topic + "-dlt", 1).getFirst();

                    assertThat(deadLetter.value()).isEqualTo("boom");
                    assertThat(headers(deadLetter, "Tenant-Id")).as("copied by the recoverer").containsExactly("acme");
                    assertThat(received(Violation.Kind.MISSING_REQUIRED)).as("no MISSING_REQUIRED").isEmpty();
                    assertThat(container(context.getBean(KafkaListenerEndpointRegistry.class)).getCommonErrorHandler())
                            .isInstanceOf(ContextErrorHandler.class);
                });
    }

    /** A transaction manager that is not Kafka-aware, for the deprecated {@code ContainerProperties.setTransactionManager}. */
    static final class TrivialTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    /** An application factory bean whose {@code @Bean} method sets a transaction manager that is not Kafka-aware, and no error handler. */
    @Configuration(proxyBeanMethods = false)
    static class PlatformTransactionFactory {

        @Bean
        @SuppressWarnings("removal")
        ConcurrentKafkaListenerContainerFactory<Object, Object> appFactory(ConsumerFactory<Object, Object> consumerFactory) {
            ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(consumerFactory);
            factory.getContainerProperties().setTransactionManager(new TrivialTransactionManager());
            return factory;
        }
    }

    @Test
    @DisplayName("KAF-03 a container with a transaction manager that is not Kafka-aware is transactional: it gets no error handler, and"
            + " its AfterRollbackProcessor is decorated")
    void platformTransactionManagerMakesTheContainerTransactional() {
        String topic = KafkaBroker.topic();
        runner(topic).withUserConfiguration(PlatformTransactionFactory.class, ApplicationFactoryListener.class).run(context -> {
            AbstractMessageListenerContainer<?, ?> container = container(context.getBean(KafkaListenerEndpointRegistry.class));

            assertThat(container.getCommonErrorHandler()).as("the transactional container's handler").isNull();
            assertThat(container.getAfterRollbackProcessor()).isInstanceOf(ContextAfterRollbackProcessor.class);
        });
    }

    /**
     * A {@code byte[]} template bean, and a {@code DefaultErrorHandler} bean, without retries, that dead-letters through it. The template
     * is a {@code KafkaTemplate} bean so that the library's {@code ProducerPostProcessor} checks its sends: a template that is not a bean,
     * with its own producer factory, would never throw {@code MISSING_REQUIRED}, and this test could not go red.
     */
    @Configuration(proxyBeanMethods = false)
    static class BytesDeadLetterHandler {

        @Bean
        KafkaTemplate<byte[], byte[]> bytesTemplate(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                    Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers), new ByteArraySerializer(), new ByteArraySerializer()));
        }

        @Bean
        CommonErrorHandler deadLetterHandler(KafkaTemplate<byte[], byte[]> bytesTemplate) {
            return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(bytesTemplate), new FixedBackOff(0L, 0L));
        }
    }

    @Test
    @DisplayName("KAF-03 the error handler runs in the record's snapshot for a deserialization failure")
    void deserializationFailureIsHandledInTheRecordsSnapshot() {
        String topic = KafkaBroker.topic();
        runner(topic).withPropertyValues(POISON_VALUES)
                .withUserConfiguration(BytesDeadLetterHandler.class, BootFactoryListener.class).run(context -> {
                    Recorder recorder = context.getBean(Recorder.class);

                    produce(topic, "poison", "Tenant-Id", "acme");

                    ConsumerRecord<String, String> deadLetter = KafkaBroker.read(topic + "-dlt", 1).getFirst();

                    assertThat(deadLetter.value()).as("the raw record").isEqualTo("poison");
                    assertThat(headers(deadLetter, "Tenant-Id")).as("copied by the recoverer").containsExactly("acme");
                    assertThat(recorder.listener).as("the listener is not invoked").isEmpty();
                });
    }

    @Test
    @DisplayName("KAF-03 a record whose value fails deserialization never has its header violations reported, and its error handling runs"
            + " in its snapshot")
    void deserializationFailureReportsNoViolations() {
        String topic = KafkaBroker.topic();
        runner(topic).withPropertyValues(POISON_VALUES)
                .withPropertyValues("boundrail.context.violations.mode=report", "test.seek-after-error=true")
                .withUserConfiguration(BootHandler.class, BootFactoryListener.class).run(context -> {
                    Recorder recorder = context.getBean(Recorder.class);

                    produce(topic, "poison", "Tenant-Id", "acme", "Tenant-Id", "acme", "Order-Count", "1");

                    await().atMost(TIMEOUT).untilAsserted(() -> assertThat(recorder.handled).as("the application's handler").hasSize(1));
                    assertThat(recorder.handled.getFirst().getValue().find(ORDER_COUNT)).as("the record's snapshot").contains(1L);
                    assertThat(recorder.listener).as("the listener is not invoked").isEmpty();
                    assertThat(received).as("no violation reported").isEmpty();
                });
    }

    @Test
    @DisplayName("KAF-03 with trust-headers=false, error handling of a record whose value fails deserialization reports no"
            + " UNTRUSTED_HEADER")
    void untrustedDeserializationFailureReportsNoUntrustedHeader() {
        String topic = KafkaBroker.topic();
        runner(topic).withPropertyValues(POISON_VALUES)
                .withPropertyValues("boundrail.context.kafka.trust-headers=false", "boundrail.context.violations.mode=report",
                        "test.seek-after-error=true")
                .withUserConfiguration(BootHandler.class, BootFactoryListener.class).run(context -> {
                    Recorder recorder = context.getBean(Recorder.class);

                    produce(topic, "poison", "Tenant-Id", "acme");

                    await().atMost(TIMEOUT).untilAsserted(() -> assertThat(recorder.handled).as("the application's handler").hasSize(1));
                    assertThat(recorder.handled.getFirst().getValue().isEmpty()).as("the empty snapshot").isTrue();
                    assertThat(recorder.listener).as("the listener is not invoked").isEmpty();
                    assertThat(received).as("no violation reported").isEmpty();
                });
    }

    /** Records the {@code ConsumerStoppedEvent}s of the containers. */
    static final class StoppedEvents implements ApplicationListener<ConsumerStoppedEvent> {

        final List<ConsumerStoppedEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onApplicationEvent(ConsumerStoppedEvent event) {
            events.add(event);
        }
    }

    @Test
    @DisplayName("KAF-03 an Error thrown while rebuilding the failed record's snapshot propagates, and the error handling does not run")
    void errorWhileRebuildingPropagates(CapturedOutput output) {
        String topic = KafkaBroker.topic();
        runner(topic).withPropertyValues(POISON_VALUES).withPropertyValues("test.seek-after-error=true")
                .withBean(StoppedEvents.class)
                .withUserConfiguration(BootHandler.class, BootFactoryListener.class).run(context -> {
                    Recorder recorder = context.getBean(Recorder.class);
                    StoppedEvents stopped = context.getBean(StoppedEvents.class);

                    produce(topic, "poison", "Tenant-Id", "acme", "Order-Count", "error");

                    await().atMost(TIMEOUT).untilAsserted(() -> assertThat(stopped.events).as("the consumer stopped")
                            .singleElement().satisfies(event -> assertThat(event.getReason()).isEqualTo(ConsumerStoppedEvent.Reason.ERROR)));
                    assertThat(output).as("the container saw the codec's Error")
                            .contains("Error handler threw an error")
                            .contains(CodecError.class.getName() + ": order-count codec error");
                    assertThat(recorder.handled).as("the application's handler is not called").isEmpty();
                    assertThat(recorder.listener).as("the listener is not invoked").isEmpty();
                });
    }

    /** The application's recording handler as a {@code CommonErrorHandler} bean that dead-letters through the template bean. */
    @Configuration(proxyBeanMethods = false)
    static class RecordingDeadLetterHandler {

        @Bean
        CommonErrorHandler applicationHandler(Recorder recorder, KafkaTemplate<Object, Object> template) {
            return new RecordingErrorHandler(recorder.handled, new DeadLetterPublishingRecoverer(template));
        }
    }

    @Test
    @DisplayName("KAF-03 when rebuilding the failed record's snapshot throws, the error handler still runs, in the empty snapshot")
    void errorHandlerRunsInTheEmptySnapshotWhenRebuildingThrows() {
        String topic = KafkaBroker.topic();
        runner(topic).withUserConfiguration(RecordingDeadLetterHandler.class, BootFactoryListener.class).run(context -> {
            Recorder recorder = context.getBean(Recorder.class);

            produce(topic, "boom", "Tenant-Id", "acme", "Order-Count", "explode");

            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(recorder.handled).as("the application's handler").hasSizeGreaterThan(1));
            context.getBean(KafkaListenerEndpointRegistry.class).stop();

            assertThat(recorder.deliveries.get()).as("redelivered").isGreaterThan(1);
            assertThat(recorder.handled).as("the application's handler, once per delivery").hasSize(recorder.deliveries.get())
                    .allSatisfy(call -> assertThat(call.getValue().isEmpty()).as("no binding").isTrue());
            assertThat(recorder.listener).as("the listener is not invoked").isEmpty();
        });
    }
}
