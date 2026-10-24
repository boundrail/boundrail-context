package io.github.boundrail.context.kafka;

import static io.github.boundrail.context.kafka.support.TestPolicies.TENANT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.ContextSnapshot;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.kafka.support.KafkaBroker;
import io.github.boundrail.context.kafka.support.TestPolicies;
import io.github.boundrail.context.spring.BoundrailContextAutoConfiguration;
import io.github.boundrail.context.spring.ViolationListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.util.backoff.FixedBackOff;

/**
 * The listener scope advice on every listener container factory bean, against the broker: Boot's factory, application factories and
 * containers they create (KAF-01, KAF-02, KAF-03, KAF-05). Records are produced by a plain {@code KafkaProducer} with exact headers.
 */
class ListenerScopeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final List<Violation> received = new CopyOnWriteArrayList<>();

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, BoundrailContextAutoConfiguration.class,
                        BoundrailKafkaAutoConfiguration.class))
                .withBean(SimpleMeterRegistry.class)
                .withBean(ViolationListener.class, () -> received::add)
                .withBean(ContextPolicy.class, TestPolicies::policy)
                .withUserConfiguration(Common.class);
    }

    /** {@link #runner()} on the broker, with {@code test.topic} set to a new topic, which listeners also use as their group id. */
    private ApplicationContextRunner brokerRunner(String topic) {
        return runner().withPropertyValues("spring.kafka.bootstrap-servers=" + KafkaBroker.bootstrapServers(),
                "spring.kafka.consumer.auto-offset-reset=earliest",
                "test.topic=" + topic);
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

    /** What the listeners, the application advice and the record interceptor observed, each with {@code Context.capture()}. */
    static final class Recorder {

        final List<ContextSnapshot> listener = new CopyOnWriteArrayList<>();
        final List<ContextSnapshot> applicationAdvice = new CopyOnWriteArrayList<>();
        final List<Map.Entry<String, ContextSnapshot>> interceptor = new CopyOnWriteArrayList<>();
        final List<ContextSnapshot> batches = new CopyOnWriteArrayList<>();
        final List<Integer> batchSizes = new CopyOnWriteArrayList<>();

        /** Advice an application sets on its factory; records the snapshot of each {@code onMessage}. */
        final MethodInterceptor advice = invocation -> {
            if (invocation.getMethod().getName().equals("onMessage")) {
                applicationAdvice.add(Context.capture());
            }
            return invocation.proceed();
        };

        /** An application {@code RecordInterceptor}; records the snapshot of each call. */
        final RecordInterceptor<Object, Object> recordInterceptor = new RecordInterceptor<>() {
            @Override
            public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
                interceptor.add(Map.entry("intercept", Context.capture()));
                return record;
            }

            @Override
            public void success(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
                interceptor.add(Map.entry("success", Context.capture()));
            }

            @Override
            public void failure(ConsumerRecord<Object, Object> record, Exception exception, Consumer<Object, Object> consumer) {
                interceptor.add(Map.entry("failure", Context.capture()));
            }

            @Override
            public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
                interceptor.add(Map.entry("afterRecord", Context.capture()));
            }
        };

        /** The record listener: records the snapshot, then throws for the value {@code boom}. */
        void onRecord(ConsumerRecord<?, ?> record) {
            listener.add(Context.capture());
            if ("boom".equals(record.value())) {
                throw new IllegalStateException("listener failure");
            }
        }

        List<ContextSnapshot> interceptor(String method) {
            return interceptor.stream().filter(entry -> entry.getKey().equals(method)).map(Map.Entry::getValue).toList();
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

        /** Picked up by Boot's factory: a failed record is not retried. */
        @Bean
        CommonErrorHandler noRetries() {
            return new DefaultErrorHandler(new FixedBackOff(0L, 0L));
        }
    }

    /** An application factory bean whose {@code @Bean} method sets its own advice, record interceptor and error handler. */
    @Configuration(proxyBeanMethods = false)
    static class ApplicationFactory {

        @Bean
        ConcurrentKafkaListenerContainerFactory<Object, Object> appFactory(ConsumerFactory<Object, Object> consumerFactory,
                Recorder recorder) {
            ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(consumerFactory);
            factory.getContainerProperties().setAdviceChain(recorder.advice);
            factory.setRecordInterceptor(recorder.recordInterceptor);
            factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(0L, 0L)));
            return factory;
        }
    }

    /** A {@code @KafkaListener} on Boot's factory, to whose advice chain it appends the application's advice. */
    static class BootFactoryListener {

        private final Recorder recorder;

        BootFactoryListener(Recorder recorder,
                @Qualifier("kafkaListenerContainerFactory") AbstractKafkaListenerContainerFactory<?, ?, ?> bootFactory) {
            this.recorder = recorder;
            // Appends the application's advice to Boot's factory, after the factory bean is initialized and before its containers exist.
            Advice[] chain = bootFactory.getContainerProperties().getAdviceChain();
            Advice[] appended = Arrays.copyOf(chain, chain.length + 1);
            appended[chain.length] = recorder.advice;
            bootFactory.getContainerProperties().setAdviceChain(appended);
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

    @Test
    @DisplayName("KAF-03 the library's advice is first in the advice chain of Boot's listener container factory and of every"
            + " AbstractKafkaListenerContainerFactory bean, ahead of the application's advice, and only once")
    void adviceIsFirstInEveryFactorysChainOnlyOnce() {
        runner().withUserConfiguration(ApplicationFactory.class).run(context -> {
            Recorder recorder = context.getBean(Recorder.class);
            AbstractKafkaListenerContainerFactory<?, ?, ?> bootFactory =
                    context.getBean("kafkaListenerContainerFactory", AbstractKafkaListenerContainerFactory.class);
            AbstractKafkaListenerContainerFactory<?, ?, ?> appFactory =
                    context.getBean("appFactory", AbstractKafkaListenerContainerFactory.class);

            assertThat(bootFactory.getContainerProperties().getAdviceChain()).as("Boot's factory")
                    .singleElement().isInstanceOf(ListenerScopeAdvice.class);
            assertThat(appFactory.getContainerProperties().getAdviceChain()).as("the application factory")
                    .hasSize(2)
                    .satisfies(chain -> {
                        assertThat(chain[0]).isInstanceOf(ListenerScopeAdvice.class);
                        assertThat(chain[1]).as("the application's advice, inside").isSameAs(recorder.advice);
                    });

            // Post-processing the same factory again leaves the chain as it is.
            context.getBean("boundrailListenerContainerFactoryPostProcessor", BeanPostProcessor.class)
                    .postProcessAfterInitialization(appFactory, "appFactory");

            assertThat(appFactory.getContainerProperties().getAdviceChain()).as("only once").hasSize(2)
                    .satisfies(chain -> assertThat(chain[1]).isSameAs(recorder.advice));
        });
    }

    @ParameterizedTest(name = "{displayName}: {0}")
    @MethodSource("listenerContainers")
    @DisplayName("KAF-03 a @KafkaListener on Boot's factory or on an application factory bean, and a container bean created with"
            + " factory.createContainer, run in their record's snapshot, with the application's advice inside it and RecordInterceptors"
            + " outside it")
    void listenersRunInTheirRecordsSnapshot(String row, Class<?>[] configurations) {
        String topic = KafkaBroker.topic();
        brokerRunner(topic).withUserConfiguration(configurations).run(context -> {
            Recorder recorder = context.getBean(Recorder.class);

            produce(topic, "ok", "Tenant-Id", "acme");
            produce(topic, "boom", "Tenant-Id", "acme");

            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(recorder.interceptor("afterRecord")).hasSize(2));
            assertThat(recorder.listener).as("the listener").hasSize(2)
                    .allSatisfy(seen -> assertThat(seen.find(TENANT)).contains("acme"));
            assertThat(recorder.applicationAdvice).as("the application's advice").hasSize(2)
                    .allSatisfy(seen -> assertThat(seen.find(TENANT)).contains("acme"));
            for (String method : List.of("intercept", "success", "failure")) {
                assertThat(recorder.interceptor(method)).as("RecordInterceptor.%s", method).isNotEmpty()
                        .allSatisfy(seen -> assertThat(seen.find(TENANT)).isEmpty());
            }
            assertThat(recorder.interceptor("afterRecord")).as("no binding on the consumer thread after each record")
                    .allSatisfy(seen -> assertThat(seen.isEmpty()).isTrue());
        });
    }

    static Stream<Arguments> listenerContainers() {
        return Stream.of(
                Arguments.of("@KafkaListener on Boot's factory", new Class<?>[] {BootFactoryListener.class}),
                Arguments.of("@KafkaListener on an application factory bean",
                        new Class<?>[] {ApplicationFactory.class, ApplicationFactoryListener.class}),
                Arguments.of("container bean from factory.createContainer",
                        new Class<?>[] {ApplicationFactory.class, CreatedContainer.class}));
    }

    @Test
    @DisplayName("KAF-02 a listener's violations are reported through the reporting of the context that owns its container")
    void violationsAreReportedByTheOwningContext() {
        String topic = KafkaBroker.topic();
        brokerRunner(topic).withUserConfiguration(ApplicationFactory.class, ApplicationFactoryListener.class).run(context -> {
            Recorder recorder = context.getBean(Recorder.class);

            produce(topic, "repeated", "Tenant-Id", "acme", "Tenant-Id", "beta");

            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(recorder.listener).hasSize(1));
            assertThat(invalidTenantCount(context.getBean(MeterRegistry.class))).isEqualTo(1.0);
        });
    }

    /** {@return the count of {@code boundrail.context.violations{kind=invalid_value,key=tenant,boundary=message}}, 0 if not registered} */
    private static double invalidTenantCount(MeterRegistry registry) {
        Counter counter = registry.find("boundrail.context.violations")
                .tags("kind", "invalid_value", "key", "tenant", "boundary", "message").counter();
        return counter == null ? 0 : counter.count();
    }

    /** A {@code @KafkaListener} on Boot's factory that sends the record's value to {@code test.out-topic} through the template bean. */
    static class ForwardingListener {

        private final KafkaTemplate<Object, Object> template;
        private final String outTopic;

        ForwardingListener(KafkaTemplate<Object, Object> template, @Value("${test.out-topic}") String outTopic) {
            this.template = template;
            this.outTopic = outTopic;
        }

        @KafkaListener(topics = "${test.topic}", groupId = "${test.topic}")
        void onRecord(ConsumerRecord<String, String> record) throws Exception {
            template.send(outTopic, record.value()).get();
        }
    }

    @Test
    @DisplayName("KAF-01 a record a listener sends carries the consumed record's MESSAGE keys")
    void listenerSendCarriesTheConsumedKeys() {
        String topic = KafkaBroker.topic();
        String outTopic = KafkaBroker.topic();
        ApplicationContextRunner runner =
                brokerRunner(topic).withPropertyValues("test.out-topic=" + outTopic).withUserConfiguration(ForwardingListener.class);
        runner.run(context -> {
            produce(topic, "forwarded", "Tenant-Id", "acme");

            ConsumerRecord<String, String> sent = KafkaBroker.read(outTopic, 1).getFirst();

            assertThat(sent.value()).isEqualTo("forwarded");
            List<Header> tenant = StreamSupport.stream(sent.headers().headers("Tenant-Id").spliterator(), false).toList();
            assertThat(tenant).singleElement().satisfies(header -> assertThat(new String(header.value(), UTF_8)).isEqualTo("acme"));
        });
    }

    /** A batch factory bean and a batch {@code @KafkaListener} on it. */
    @Configuration(proxyBeanMethods = false)
    static class BatchListener {

        @Bean
        ConcurrentKafkaListenerContainerFactory<Object, Object> batchFactory(ConsumerFactory<Object, Object> consumerFactory) {
            ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(consumerFactory);
            factory.setBatchListener(true);
            return factory;
        }

        @Bean
        BatchRecipient batchRecipient(Recorder recorder) {
            return new BatchRecipient(recorder);
        }
    }

    static class BatchRecipient {

        private final Recorder recorder;

        BatchRecipient(Recorder recorder) {
            this.recorder = recorder;
        }

        @KafkaListener(topics = "${test.topic}", groupId = "${test.topic}", containerFactory = "batchFactory")
        void onBatch(List<ConsumerRecord<String, String>> records) {
            recorder.batches.add(Context.capture());
            recorder.batchSizes.add(records.size());
        }
    }

    @Test
    @DisplayName("KAF-05 a batch @KafkaListener is not scoped, and nothing is decoded or reported for its records")
    void batchListenerIsNotScoped() {
        String topic = KafkaBroker.topic();
        brokerRunner(topic).withUserConfiguration(BatchListener.class).run(context -> {
            Recorder recorder = context.getBean(Recorder.class);

            produce(topic, "first", "Tenant-Id", "acme");
            produce(topic, "repeated", "Tenant-Id", "acme", "Tenant-Id", "beta");

            await().atMost(TIMEOUT).untilAsserted(() ->
                    assertThat(recorder.batchSizes.stream().mapToInt(Integer::intValue).sum()).isEqualTo(2));
            assertThat(recorder.batches).as("the batch listener sees the consumer thread's empty snapshot")
                    .allSatisfy(seen -> assertThat(seen.find(TENANT)).isEmpty());
            assertThat(received).as("nothing reported").isEmpty();
        });
    }
}
