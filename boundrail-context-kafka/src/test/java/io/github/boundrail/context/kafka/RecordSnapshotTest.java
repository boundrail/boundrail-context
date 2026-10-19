package io.github.boundrail.context.kafka;

import static io.github.boundrail.context.Boundary.MESSAGE;
import static io.github.boundrail.context.Violation.Kind.INVALID_VALUE;
import static io.github.boundrail.context.Violation.Kind.OVERSIZED_VALUE;
import static io.github.boundrail.context.kafka.support.TestPolicies.ORDER_COUNT;
import static io.github.boundrail.context.kafka.support.TestPolicies.PRIORITY;
import static io.github.boundrail.context.kafka.support.TestPolicies.TENANT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextCodec;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.ContextSnapshot;
import io.github.boundrail.context.Initiator;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.kafka.support.TestPolicies;
import io.github.boundrail.context.spring.BoundrailContextAutoConfiguration;
import io.github.boundrail.context.spring.ViolationListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The snapshot {@code KafkaContext.forRecord} builds for a trusted record (KAF-02, KAF-05, CORE-17). */
@ExtendWith(OutputCaptureExtension.class)
class RecordSnapshotTest {

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

    /** {@return a record carrying the headers given as name and value pairs, in order; each value encoded as UTF-8} */
    private static ConsumerRecord<String, String> record(String... namesAndValues) {
        RecordHeaders headers = new RecordHeaders();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            headers.add(namesAndValues[i], namesAndValues[i + 1].getBytes(UTF_8));
        }
        return record(headers);
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

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    @Test
    @DisplayName("KAF-02 a trusted record's snapshot holds exactly the MESSAGE keys decoded from their wire headers")
    void holdsTheDecodedMessageKeys() {
        runner().run(context -> {
            KafkaContext kafka = context.getBean(KafkaContext.class);
            ConsumerRecord<String, String> record = record("Tenant-Id", "Zürich", "Order-Count", "7", "Boundrail-Initiator", "user:alice");

            ContextSnapshot snapshot = Context.with(PRIORITY, 1L).call(() -> kafka.forRecord(record));

            assertThat(snapshot.keys()).as("exactly the decoded keys, none of the caller's").containsExactlyInAnyOrder(TENANT, ORDER_COUNT, BuiltInKeys.INITIATOR);
            assertThat(snapshot.find(TENANT)).contains("Zürich");
            assertThat(snapshot.find(ORDER_COUNT)).contains(7L);
            assertThat(snapshot.find(BuiltInKeys.INITIATOR)).contains(Initiator.of("user:alice"));
            assertThat(received).isEmpty();
        });
    }

    @Test
    @DisplayName("KAF-02 headers of keys that do not propagate to MESSAGE, Boundrail-Deadline-Ms, Authorization and other names are ignored"
            + " and not reported, and wire names are compared exactly")
    void ignoresOtherHeadersAndComparesNamesExactly() {
        runner().run(context -> {
            ContextSnapshot snapshot = context.getBean(KafkaContext.class).forRecord(record(
                    "Tenant-Id", "acme",
                    "Priority", "5",
                    "Boundrail-Deadline-Ms", "5000",
                    "Authorization", "Bearer x",
                    "X-Other", "other",
                    "tenant-id", "evil"));

            assertThat(snapshot.keys()).containsExactly(TENANT);
            assertThat(snapshot.find(TENANT)).contains("acme");
            assertThat(snapshot.find(BuiltInKeys.DEADLINE)).isEmpty();
            assertThat(snapshot.find(BuiltInKeys.AUTHORITY)).as("ID-02").isEmpty();
            assertThat(received).isEmpty();
        });
    }

    @Test
    @DisplayName("KAF-02 a record without context headers gives the empty snapshot")
    void recordWithoutContextHeadersGivesTheEmptySnapshot() {
        runner().run(context -> {
            KafkaContext kafka = context.getBean(KafkaContext.class);

            ContextSnapshot snapshot = Context.with(TENANT, "outer").call(() -> kafka.forRecord(record("X-Other", "other")));

            assertThat(snapshot.isEmpty()).as("empty, not the caller's snapshot").isTrue();
            assertThat(received).isEmpty();
        });
    }

    @Test
    @DisplayName("KAF-02 a decode failure leaves the key unbound and reports one INVALID_VALUE with boundary MESSAGE and no destination, and an"
            + " empty Boundrail-Initiator is a decode failure")
    void decodeFailureLeavesTheKeyUnboundAndIsReported() {
        runner().run(context -> {
            ContextSnapshot snapshot = context.getBean(KafkaContext.class)
                    .forRecord(record("Tenant-Id", "acme", "Order-Count", "abc", "Boundrail-Initiator", ""));

            assertThat(snapshot.keys()).as("the other keys are still bound").containsExactly(TENANT);
            assertThat(snapshot.find(TENANT)).contains("acme");
            assertThat(received)
                    .extracting(Violation::kind, Violation::key, Violation::boundary, Violation::destination)
                    .containsExactlyInAnyOrder(
                            tuple(INVALID_VALUE, "order-count", MESSAGE, Optional.empty()),
                            tuple(INVALID_VALUE, "boundrail.initiator", MESSAGE, Optional.empty()));
        });
    }

    @ParameterizedTest(name = "{0} Tenant-Id headers")
    @ValueSource(ints = {2, 3})
    @DisplayName("KAF-02 a wire header carried more than once leaves the key unbound, decodes none of its values and reports one INVALID_VALUE")
    void repeatedHeaderLeavesTheKeyUnbound(int copies) {
        AtomicInteger decodes = new AtomicInteger();
        runner(TestPolicies.withTenantCodec(counting(decodes))).run(context -> {
            RecordHeaders headers = new RecordHeaders();
            headers.add("Order-Count", "7".getBytes(UTF_8));
            headers.add("Boundrail-Initiator", "user:alice".getBytes(UTF_8));
            for (int i = 0; i < copies; i++) {
                headers.add("Tenant-Id", ("acme-" + i).getBytes(UTF_8));
            }

            ContextSnapshot snapshot = context.getBean(KafkaContext.class).forRecord(record(headers));

            assertThat(snapshot.keys()).as("other keys still bound").containsExactlyInAnyOrder(ORDER_COUNT, BuiltInKeys.INITIATOR);
            assertThat(decodes).as("none of the values decoded").hasValue(0);
            assertThat(received)
                    .extracting(Violation::kind, Violation::key, Violation::boundary, Violation::destination)
                    .containsExactly(tuple(INVALID_VALUE, "tenant", MESSAGE, Optional.empty()));
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nullValueHeaders")
    @DisplayName("KAF-02 a header with a null value leaves the key unbound and reports one INVALID_VALUE")
    void nullValueLeavesTheKeyUnbound(String wireName, String keyName) {
        runner().run(context -> {
            RecordHeaders headers = new RecordHeaders();
            headers.add("Order-Count", "7".getBytes(UTF_8));
            headers.add(wireName, null);

            ContextSnapshot snapshot = context.getBean(KafkaContext.class).forRecord(record(headers));

            assertThat(snapshot.keys()).as("other keys still bound").containsExactly(ORDER_COUNT);
            assertThat(received)
                    .extracting(Violation::kind, Violation::key, Violation::boundary, Violation::destination)
                    .containsExactly(tuple(INVALID_VALUE, keyName, MESSAGE, Optional.empty()));
        });
    }

    static Stream<Arguments> nullValueHeaders() {
        return Stream.of(Arguments.of("Tenant-Id", "tenant"), Arguments.of("Boundrail-Initiator", "boundrail.initiator"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lengths")
    @DisplayName("KAF-02 WIRE-04 applies first, to the value decoded as UTF-8 with replacement in UTF-16 code units: a longer value is unbound"
            + " with one OVERSIZED_VALUE, also when it is malformed")
    void lengthIsCheckedFirstInUtf16CodeUnits(String row, byte[] value, Integer maxValueLength, boolean bound) {
        ApplicationContextRunner runner = maxValueLength == null
                ? runner()
                : runner().withPropertyValues("boundrail.context.wire.max-value-length=" + maxValueLength);
        runner.run(context -> {
            RecordHeaders headers = new RecordHeaders();
            headers.add("Tenant-Id", value);

            ContextSnapshot snapshot = context.getBean(KafkaContext.class).forRecord(record(headers));

            if (bound) {
                assertThat(snapshot.find(TENANT)).contains(new String(value, UTF_8));
                assertThat(received).isEmpty();
            } else {
                assertThat(snapshot.isEmpty()).isTrue();
                assertThat(received)
                        .extracting(Violation::kind, Violation::key, Violation::boundary, Violation::destination)
                        .containsExactly(tuple(OVERSIZED_VALUE, "tenant", MESSAGE, Optional.empty()));
            }
        });
    }

    static Stream<Arguments> lengths() {
        byte[] malformed = new byte[300];
        Arrays.fill(malformed, (byte) 0x80);
        return Stream.of(
                Arguments.of("256 'a' bound", "a".repeat(256).getBytes(UTF_8), null, true),
                Arguments.of("257 'a' not bound", "a".repeat(257).getBytes(UTF_8), null, false),
                Arguments.of("256 'é' (512 bytes) bound", "é".repeat(256).getBytes(UTF_8), null, true),
                Arguments.of("128 U+1F600 (256 code units) bound", "\uD83D\uDE00".repeat(128).getBytes(UTF_8), null, true),
                Arguments.of("129 U+1F600 (258 code units) not bound", "\uD83D\uDE00".repeat(129).getBytes(UTF_8), null, false),
                Arguments.of("300 bytes of 0x80: OVERSIZED_VALUE only", malformed, null, false),
                Arguments.of("max-value-length=10, 11 'a' not bound", "a".repeat(11).getBytes(UTF_8), 10, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedUtf8")
    @DisplayName("KAF-02 a value that is not well-formed UTF-8 leaves the key unbound, reports one INVALID_VALUE and is not decoded")
    void malformedUtf8LeavesTheKeyUnbound(String row, byte[] value) {
        AtomicInteger decodes = new AtomicInteger();
        runner(TestPolicies.withTenantCodec(counting(decodes))).run(context -> {
            RecordHeaders headers = new RecordHeaders();
            headers.add("Tenant-Id", value);

            ContextSnapshot snapshot = context.getBean(KafkaContext.class).forRecord(record(headers));

            assertThat(snapshot.isEmpty()).isTrue();
            assertThat(decodes).as("not decoded").hasValue(0);
            assertThat(received)
                    .extracting(Violation::kind, Violation::key, Violation::boundary, Violation::destination)
                    .containsExactly(tuple(INVALID_VALUE, "tenant", MESSAGE, Optional.empty()));
        });
    }

    static Stream<Arguments> malformedUtf8() {
        return Stream.of(
                Arguments.of("80 (lone continuation byte)", bytes(0x80)),
                Arguments.of("C0 80 (overlong)", bytes(0xC0, 0x80)),
                Arguments.of("ED A0 80 (surrogate)", bytes(0xED, 0xA0, 0x80)),
                Arguments.of("E2 82 (truncated)", bytes(0xE2, 0x82)));
    }

    @Test
    @DisplayName("KAF-02 an exception other than IllegalArgumentException from decode propagates unchanged and nothing is reported")
    void otherDecodeExceptionPropagatesUnchanged() {
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
            Throwable thrown = catchThrowable(() -> context.getBean(KafkaContext.class).forRecord(record("Tenant-Id", "acme")));

            assertThat(thrown).isSameAs(failure);
            assertThat(received).isEmpty();
        });
    }

    @Test
    @DisplayName("KAF-02 violations are reported through the reporting of the context that owns the KafkaContext, and never contain the value")
    void violationsAreReportedInTheOwningContextWithoutTheValue(CapturedOutput output) {
        runner().run(context -> {
            context.getBean(KafkaContext.class).forRecord(record("Order-Count", "abc"));

            assertThat(context.getBean(MeterRegistry.class).get("boundrail.context.violations")
                    .tags("kind", "invalid_value", "key", "order-count", "boundary", "message").counter().count()).isEqualTo(1.0);
            assertThat(output.getOut().lines().filter(line -> line.contains("WARN") && line.contains("INVALID_VALUE")))
                    .singleElement().asString().contains("key order-count", "boundary MESSAGE");
            assertThat(received).singleElement().satisfies(violation -> {
                assertThat(violation.thread()).as("DIAG-04").isEqualTo(Thread.currentThread().getName());
                assertThat(violation.toString()).as("DIAG-09").doesNotContain("abc");
            });
            assertThat(output.getAll()).as("DIAG-09").doesNotContain("abc");
        });
    }

    @Test
    @DisplayName("KAF-05 forRecord does not enter the snapshot, and each call reports the record's violations again")
    void forRecordDoesNotEnterTheSnapshotAndReportsPerCall() {
        runner().run(context -> {
            KafkaContext kafka = context.getBean(KafkaContext.class);
            ConsumerRecord<String, String> record = record("Tenant-Id", "acme", "Order-Count", "abc");

            Context.with(TENANT, "outer").run(() -> {
                ContextSnapshot before = Context.capture();

                ContextSnapshot first = kafka.forRecord(record);
                ContextSnapshot afterFirst = Context.capture();
                kafka.forRecord(record);

                assertThat(first.find(TENANT)).contains("acme");
                assertThat(afterFirst.keys()).isEqualTo(before.keys());
                assertThat(afterFirst.find(TENANT)).contains("outer");
                assertThat(Context.capture().find(TENANT)).contains("outer");
            });
            assertThat(received)
                    .extracting(Violation::kind, Violation::key)
                    .containsExactly(tuple(INVALID_VALUE, "order-count"), tuple(INVALID_VALUE, "order-count"));
        });
    }

    @Test
    @DisplayName("CORE-17 forRecord(null) throws NullPointerException")
    void forRecordRejectsNull() {
        runner().run(context -> {
            KafkaContext kafka = context.getBean(KafkaContext.class);

            assertThatNullPointerException().isThrownBy(() -> kafka.forRecord(null));
        });
    }
}
