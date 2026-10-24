package io.github.boundrail.context.kafka;

import static io.github.boundrail.context.Boundary.MESSAGE;
import static io.github.boundrail.context.Violation.Kind.UNTRUSTED_HEADER;
import static io.github.boundrail.context.kafka.support.TestPolicies.TENANT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.ContextCodec;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.ContextSnapshot;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.kafka.support.TestPolicies;
import io.github.boundrail.context.spring.BoundrailContextAutoConfiguration;
import io.github.boundrail.context.spring.ViolationListener;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The snapshot {@code KafkaContext.forRecord} builds under {@code kafka.trust-headers} (KAF-04). */
class RecordTrustTest {

    private final List<Violation> received = new CopyOnWriteArrayList<>();

    private ApplicationContextRunner runner(ContextPolicy policy) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class, BoundrailKafkaAutoConfiguration.class))
                .withBean(SimpleMeterRegistry.class)
                .withBean(ViolationListener.class, () -> received::add)
                .withBean(ContextPolicy.class, () -> policy);
    }

    /** {@return a record carrying {@code headers}} */
    private static ConsumerRecord<String, String> record(RecordHeaders headers) {
        return new ConsumerRecord<>("orders", 0, 0L, 0L, TimestampType.CREATE_TIME, 0, 0, "key", "value", headers, Optional.empty());
    }

    /** {@return a string codec counting its {@code decode} calls in {@code calls}} */
    private static ContextCodec<String> counting(AtomicInteger calls) {
        return new ContextCodec<>() {
            @Override
            public String encode(String value) {
                return value;
            }

            @Override
            public String decode(String text) {
                calls.incrementAndGet();
                return Codecs.string().decode(text);
            }
        };
    }

    @Test
    @DisplayName("KAF-04 with trust-headers=false the snapshot is empty and one UNTRUSTED_HEADER is reported per policy wire header name"
            + " present, however often and whatever its value")
    void reportsEachPolicyWireHeaderOnce() {
        AtomicInteger decodes = new AtomicInteger();
        runner(TestPolicies.withTenantCodec(counting(decodes)))
                .withPropertyValues("boundrail.context.kafka.trust-headers=false")
                .run(context -> {
                    RecordHeaders headers = new RecordHeaders();
                    headers.add("Tenant-Id", "acme".getBytes(UTF_8));
                    headers.add("Tenant-Id", "acme".getBytes(UTF_8));
                    headers.add("Boundrail-Initiator", null);
                    headers.add("Boundrail-Deadline-Ms", "5000".getBytes(UTF_8));
                    headers.add("Priority", "5".getBytes(UTF_8));
                    headers.add("Order-Count", "1".repeat(257).getBytes(UTF_8));
                    headers.add("Authorization", "Bearer x".getBytes(UTF_8));
                    headers.add("X-Other", "other".getBytes(UTF_8));
                    headers.add("tenant-id", "evil".getBytes(UTF_8));

                    ContextSnapshot snapshot = context.getBean(KafkaContext.class).forRecord(record(headers));

                    assertThat(snapshot.keys()).isEmpty();
                    assertThat(snapshot.find(BuiltInKeys.AUTHORITY)).as("ID-02").isEmpty();
                    assertThat(snapshot.find(BuiltInKeys.DEADLINE)).as("ID-02").isEmpty();
                    assertThat(received)
                            .extracting(Violation::kind, Violation::key, Violation::boundary, Violation::destination)
                            .containsExactlyInAnyOrder(
                                    tuple(UNTRUSTED_HEADER, "tenant", MESSAGE, Optional.empty()),
                                    tuple(UNTRUSTED_HEADER, "boundrail.initiator", MESSAGE, Optional.empty()),
                                    tuple(UNTRUSTED_HEADER, "boundrail.deadline", MESSAGE, Optional.empty()),
                                    tuple(UNTRUSTED_HEADER, "priority", MESSAGE, Optional.empty()),
                                    tuple(UNTRUSTED_HEADER, "order-count", MESSAGE, Optional.empty()));
                    assertThat(decodes).hasValue(0);

                    RecordHeaders lowerCase = new RecordHeaders();
                    lowerCase.add("tenant-id", "acme".getBytes(UTF_8));
                    lowerCase.add("boundrail-initiator", "user:alice".getBytes(UTF_8));

                    assertThat(context.getBean(KafkaContext.class).forRecord(record(lowerCase)).keys()).as("names compared exactly")
                            .isEmpty();
                    assertThat(received).as("names compared exactly: still the five above").hasSize(5);
                });
    }

    @Test
    @DisplayName("KAF-04 trust-headers=off disables trust and the default trusts")
    void offDisablesTrustAndDefaultTrusts() {
        RecordHeaders headers = new RecordHeaders();
        headers.add("Tenant-Id", "acme".getBytes(UTF_8));
        ConsumerRecord<String, String> record = record(headers);

        runner(TestPolicies.policy())
                .withPropertyValues("boundrail.context.kafka.trust-headers=off")
                .run(context -> assertThat(context.getBean(KafkaContext.class).forRecord(record).keys()).as("off").isEmpty());
        runner(TestPolicies.policy())
                .run(context -> assertThat(context.getBean(KafkaContext.class).forRecord(record).find(TENANT))
                        .as("unset").contains("acme"));
    }
}
