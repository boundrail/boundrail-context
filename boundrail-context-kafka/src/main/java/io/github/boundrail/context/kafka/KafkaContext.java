package io.github.boundrail.context.kafka;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextBinding;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.ContextSnapshot;
import io.github.boundrail.context.Initiator;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.spring.internal.AdapterSupport;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/** Provided as a bean by the Kafka auto-configuration; no public constructor (KAF-05, DESIGN D76). */
public final class KafkaContext {

    private final AdapterSupport support;
    private final boolean trustHeaders;

    KafkaContext(AdapterSupport support, boolean trustHeaders) {
        this.support = support;
        this.trustHeaders = trustHeaders;
    }

    /**
     * The snapshot KAF-02 would bind for the record, under KAF-04's trust setting; reports the same violations. The snapshot is not current
     * when this returns; scope listeners may observe one enter and exit pair, because it is built in a temporary scope (KAF-05).
     *
     * @param record the record
     * @return the record's snapshot
     * @throws NullPointerException if the record is {@code null}
     */
    public ContextSnapshot forRecord(ConsumerRecord<?, ?> record) {
        Objects.requireNonNull(record, "record");
        return snapshot(record);
    }

    /**
     * {@return the record's snapshot: exactly the bindings decoded from its headers (KAF-02)} For each key of the effective policy that
     * propagates to {@code MESSAGE}, except {@code AUTHORITY} and {@code DEADLINE}, whose wire header is present (names compared exactly,
     * WIRE-06), the value decoded as UTF-8 is decoded with {@code Initiator.of} for the initiator (WIRE-02), otherwise with the key's codec.
     * A decoding that throws {@code IllegalArgumentException} leaves the key unbound and is reported as {@code INVALID_VALUE} with boundary
     * {@code MESSAGE} and no destination (WIRE-05, DIAG-08), without the value (DIAG-09); any other exception propagates unchanged. Every
     * other header is ignored. The snapshot is built in a temporary scope, which is left before this returns (KAF-05, DESIGN D78).
     */
    ContextSnapshot snapshot(ConsumerRecord<?, ?> record) {
        ContextPolicy policy = support.policy();
        ContextBinding binding = null;
        for (ContextKey<?> key : policy.keys()) {
            if (key != BuiltInKeys.DEADLINE && key != BuiltInKeys.AUTHORITY && policy.propagates(key, Boundary.MESSAGE)) {
                binding = bind(binding, policy, key, record);
            }
        }
        if (binding == null) {
            return ContextSnapshot.EMPTY;
        }
        ContextBinding decoded = binding;
        return ContextSnapshot.EMPTY.call(() -> decoded.call(Context::capture));
    }

    /**
     * Adds the key's value decoded from its wire header, if the header is present and decodes. A key that propagates to {@code MESSAGE}
     * has a wire name, and a codec unless it is the initiator (POL-03).
     */
    private <T> ContextBinding bind(ContextBinding binding, ContextPolicy policy, ContextKey<T> key, ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(policy.wireName(key).orElseThrow());
        if (header == null) {
            return binding;
        }
        String text = new String(header.value(), UTF_8);
        T value;
        try {
            value = key == BuiltInKeys.INITIATOR
                    ? key.type().cast(Initiator.of(text))
                    : policy.codec(key).orElseThrow().decode(text);
        } catch (IllegalArgumentException e) {
            support.report(support.violation(Violation.Kind.INVALID_VALUE, key.name(), Boundary.MESSAGE));
            return binding;
        }
        return binding == null ? Context.with(key, value) : binding.with(key, value);
    }
}
