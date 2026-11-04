package io.github.boundrail.context.kafka;

import static io.github.boundrail.context.Boundary.MESSAGE;
import static io.github.boundrail.context.Violation.Kind.INVALID_VALUE;
import static io.github.boundrail.context.kafka.support.TestPolicies.ORDER_COUNT;
import static io.github.boundrail.context.kafka.support.TestPolicies.PRIORITY;
import static io.github.boundrail.context.kafka.support.TestPolicies.TENANT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextCodec;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.ContextSnapshot;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.kafka.support.TestPolicies;
import io.github.boundrail.context.spring.BoundrailContextAutoConfiguration;
import io.github.boundrail.context.spring.ViolationListener;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.listener.BatchMessageListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.kafka.listener.MessageListener;

/**
 * The listener scope advice, applied through a {@link ProxyFactory} as {@code ContainerProperties} applies its advice chain (KAF-02, KAF-03,
 * KAF-05).
 */
class ListenerScopeAdviceTest {

    private final List<Violation> received = new CopyOnWriteArrayList<>();

    private ApplicationContextRunner runner(ContextPolicy policy) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class, BoundrailKafkaAutoConfiguration.class))
                .withBean(SimpleMeterRegistry.class)
                .withBean(ViolationListener.class, () -> received::add)
                .withBean(ContextPolicy.class, () -> policy);
    }

    private ApplicationContextRunner runner() {
        return runner(TestPolicies.policy());
    }

    /** {@return {@code listener} behind a proxy with the advice built from the context's {@code KafkaContext}, as a container applies it} */
    @SuppressWarnings("unchecked")
    private static <L> L advised(L listener, KafkaContext kafka) {
        ProxyFactory factory = new ProxyFactory(listener);
        factory.addAdvice(new ListenerScopeAdvice(kafka));
        return (L) factory.getProxy();
    }

    /** {@return a record carrying the headers given as name and value pairs, in order; each value encoded as UTF-8} */
    private static ConsumerRecord<String, String> record(String... namesAndValues) {
        RecordHeaders headers = new RecordHeaders();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            headers.add(namesAndValues[i], namesAndValues[i + 1].getBytes(UTF_8));
        }
        return new ConsumerRecord<>("orders", 0, 0L, 0L, TimestampType.CREATE_TIME, 0, 0, "key", "value", headers, Optional.empty());
    }

    /** A record listener that records {@code Context.capture()} on each invocation, then throws {@code failure} if one is given. */
    private static final class RecordingListener implements MessageListener<String, String> {

        final List<ContextSnapshot> seen = new CopyOnWriteArrayList<>();
        private final Throwable failure;

        RecordingListener() {
            this(null);
        }

        RecordingListener(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void onMessage(ConsumerRecord<String, String> data) {
            seen.add(Context.capture());
            if (failure instanceof RuntimeException e) {
                throw e;
            }
            if (failure instanceof Error e) {
                throw e;
            }
        }
    }

    @Test
    @DisplayName("KAF-02 a record listener runs in exactly its record's snapshot, replacing the current one, which is restored afterwards")
    void runsInExactlyTheRecordsSnapshot() {
        runner().run(context -> {
            RecordingListener listener = new RecordingListener();
            MessageListener<String, String> advised = advised(listener, context.getBean(KafkaContext.class));

            Context.with(TENANT, "outer").with(PRIORITY, 1L).run(() -> {
                advised.onMessage(record("Tenant-Id", "acme"));

                assertThat(Context.capture().find(TENANT)).as("outer restored").contains("outer");
                assertThat(Context.capture().find(PRIORITY)).as("outer restored").contains(1L);
            });
            assertThat(listener.seen).singleElement().satisfies(seen -> {
                assertThat(seen.keys()).as("exactly the record's bindings").containsExactly(TENANT);
                assertThat(seen.find(TENANT)).contains("acme");
            });
        });
    }

    @Test
    @DisplayName("KAF-02 a record without context headers runs in the empty snapshot, replacing the current one")
    void recordWithoutContextHeadersRunsInTheEmptySnapshot() {
        runner().run(context -> {
            RecordingListener listener = new RecordingListener();
            MessageListener<String, String> advised = advised(listener, context.getBean(KafkaContext.class));

            Context.with(TENANT, "outer").run(() -> {
                advised.onMessage(record("X-Other", "other"));

                assertThat(Context.capture().find(TENANT)).as("outer restored").contains("outer");
            });
            assertThat(listener.seen).singleElement().satisfies(seen -> assertThat(seen.isEmpty()).as("empty, not the outer snapshot").isTrue());
        });
    }

    @Test
    @DisplayName("KAF-02 consecutive records on one thread see only their own bindings")
    void consecutiveRecordsSeeOnlyTheirOwnBindings() {
        runner().run(context -> {
            RecordingListener listener = new RecordingListener();
            MessageListener<String, String> advised = advised(listener, context.getBean(KafkaContext.class));

            advised.onMessage(record("Tenant-Id", "acme", "Order-Count", "7"));
            advised.onMessage(record());
            advised.onMessage(record("Tenant-Id", "beta"));

            assertThat(listener.seen).hasSize(3);
            assertThat(listener.seen.get(0).find(TENANT)).contains("acme");
            assertThat(listener.seen.get(0).find(ORDER_COUNT)).contains(7L);
            assertThat(listener.seen.get(1).isEmpty()).as("nothing of the first record").isTrue();
            assertThat(listener.seen.get(2).keys()).as("no order-count of the first record").containsExactly(TENANT);
            assertThat(listener.seen.get(2).find(TENANT)).contains("beta");
        });
    }

    @ParameterizedTest(name = "{displayName}: {0}")
    @MethodSource("listenerFailures")
    @DisplayName("KAF-02 an exception the listener throws propagates unchanged and the previous snapshot is restored")
    void listenerExceptionPropagatesUnchangedAndTheSnapshotIsRestored(String row, Throwable failure) {
        runner().run(context -> {
            RecordingListener listener = new RecordingListener(failure);
            MessageListener<String, String> advised = advised(listener, context.getBean(KafkaContext.class));

            Context.with(TENANT, "outer").run(() -> {
                Throwable thrown = catchThrowable(() -> advised.onMessage(record("Tenant-Id", "acme")));

                assertThat(thrown).isSameAs(failure);
                assertThat(Context.capture().find(TENANT)).as("outer restored").contains("outer");
            });
            assertThat(listener.seen).singleElement().satisfies(seen -> assertThat(seen.find(TENANT)).contains("acme"));
        });
    }

    static Stream<Arguments> listenerFailures() {
        return Stream.of(
                Arguments.of("RuntimeException", new IllegalStateException("listener failure")),
                Arguments.of("Error", new AssertionError("listener error")));
    }

    @Test
    @DisplayName("KAF-02 an exception other than IllegalArgumentException from decode propagates as the listener's failure, the listener is not"
            + " invoked, and nothing is reported")
    void otherDecodeExceptionPropagatesAndTheListenerIsNotInvoked() {
        IllegalStateException failure = new IllegalStateException("codec failure");
        ContextCodec<String> throwing = new ContextCodec<>() {
            @Override
            public String encode(String value) {
                return value;
            }

            @Override
            public String decode(String text) {
                throw failure;
            }
        };

        runner(TestPolicies.withTenantCodec(throwing)).run(context -> {
            RecordingListener listener = new RecordingListener();
            MessageListener<String, String> advised = advised(listener, context.getBean(KafkaContext.class));

            Throwable thrown = catchThrowable(() -> advised.onMessage(record("Tenant-Id", "acme")));

            assertThat(thrown).isSameAs(failure);
            assertThat(listener.seen).as("not invoked").isEmpty();
            assertThat(received).isEmpty();
        });
    }

    @Test
    @DisplayName("KAF-02 each delivery builds its snapshot again and reports its violations again")
    void eachDeliveryBuildsAndReportsAgain() {
        runner().run(context -> {
            RecordingListener listener = new RecordingListener();
            MessageListener<String, String> advised = advised(listener, context.getBean(KafkaContext.class));
            ConsumerRecord<String, String> record = record("Tenant-Id", "acme", "Tenant-Id", "beta", "Order-Count", "7");

            advised.onMessage(record);
            advised.onMessage(record);

            assertThat(listener.seen).hasSize(2).allSatisfy(seen -> {
                assertThat(seen.keys()).as("the repeated key unbound").containsExactly(ORDER_COUNT);
                assertThat(seen.find(ORDER_COUNT)).contains(7L);
            });
            assertThat(received)
                    .extracting(Violation::kind, Violation::key, Violation::boundary, Violation::destination)
                    .containsExactly(
                            tuple(INVALID_VALUE, "tenant", MESSAGE, Optional.empty()),
                            tuple(INVALID_VALUE, "tenant", MESSAGE, Optional.empty()));
        });
    }

    @Test
    @DisplayName("KAF-05 a BatchMessageListener is not scoped: it runs in the current snapshot, and nothing is decoded or reported for its records")
    void batchListenerIsNotScoped() {
        AtomicInteger decodes = new AtomicInteger();
        ContextCodec<String> counting = new ContextCodec<>() {
            @Override
            public String encode(String value) {
                return value;
            }

            @Override
            public String decode(String text) {
                decodes.incrementAndGet();
                return Codecs.string().decode(text);
            }
        };

        runner(TestPolicies.withTenantCodec(counting)).run(context -> {
            List<ContextSnapshot> seen = new CopyOnWriteArrayList<>();
            BatchMessageListener<String, String> listener = records -> seen.add(Context.capture());
            BatchMessageListener<String, String> advised = advised(listener, context.getBean(KafkaContext.class));

            Context.with(TENANT, "outer").run(() -> advised.onMessage(List.of(
                    record("Tenant-Id", "acme"),
                    record("Tenant-Id", "acme", "Tenant-Id", "beta"))));

            assertThat(seen).singleElement().satisfies(snapshot -> {
                assertThat(snapshot.keys()).containsExactly(TENANT);
                assertThat(snapshot.find(TENANT)).as("the current snapshot").contains("outer");
            });
            assertThat(decodes).as("nothing decoded").hasValue(0);
            assertThat(received).as("nothing reported").isEmpty();
        });
    }

    @Test
    @DisplayName("KAF-03 listener methods other than onMessage with a record pass through unscoped")
    void otherListenerMethodsPassThrough() {
        runner().run(context -> {
            List<ContextSnapshot> seen = new CopyOnWriteArrayList<>();
            final class SeekAwareListener implements MessageListener<String, String>, ConsumerSeekAware {

                @Override
                public void onMessage(ConsumerRecord<String, String> data) {
                }

                @Override
                public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
                    seen.add(Context.capture());
                }
            }
            SeekAwareListener listener = new SeekAwareListener();
            ConsumerSeekAware advised = (ConsumerSeekAware) advised((MessageListener<String, String>) listener, context.getBean(KafkaContext.class));

            Context.with(TENANT, "outer").run(() -> advised.onPartitionsAssigned(Map.of(new TopicPartition("orders", 0), 0L), null));

            assertThat(seen).singleElement().satisfies(snapshot -> {
                assertThat(snapshot.keys()).containsExactly(TENANT);
                assertThat(snapshot.find(TENANT)).as("the outer snapshot").contains("outer");
            });
        });
    }
}
