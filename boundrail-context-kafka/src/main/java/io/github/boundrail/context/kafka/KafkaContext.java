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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.Iterator;
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
     * WIRE-06), a header present once, with a value of at most {@code maxValueLength()} that is well-formed UTF-8 (DESIGN D74, WIRE-04), is
     * decoded with {@code Initiator.of} for the initiator (WIRE-02), otherwise with the key's codec.
     * A decoding that throws {@code IllegalArgumentException} leaves the key unbound and is reported as {@code INVALID_VALUE} with boundary
     * {@code MESSAGE} and no destination (WIRE-05, DIAG-08), without the value (DIAG-09); any other exception propagates unchanged. A
     * repeated header, a {@code null} value or malformed UTF-8 is reported as {@code INVALID_VALUE}, and a longer value as
     * {@code OVERSIZED_VALUE} (see {@code bind}). Every other header is ignored. With {@code trust-headers=false}, the snapshot is empty
     * and each policy wire header present is reported once as {@code UNTRUSTED_HEADER} (KAF-04). The snapshot is built in a temporary
     * scope, which is left before this returns (KAF-05, DESIGN D78).
     */
    ContextSnapshot snapshot(ConsumerRecord<?, ?> record) {
        return build(record, true);
    }

    /**
     * {@return the record's snapshot, built as {@link #snapshot} builds it but reporting nothing, so the error handling of a delivery does
     * not report its violations again (KAF-03, DESIGN D79)} Exceptions propagate as from {@code snapshot}.
     */
    ContextSnapshot rebuild(ConsumerRecord<?, ?> record) {
        return build(record, false);
    }

    private ContextSnapshot build(ConsumerRecord<?, ?> record, boolean report) {
        ContextPolicy policy = support.policy();
        if (!trustHeaders) {
            if (report) {
                reportUntrusted(policy, record);
            }
            return ContextSnapshot.EMPTY;
        }
        ContextBinding binding = null;
        for (ContextKey<?> key : policy.keys()) {
            if (key != BuiltInKeys.DEADLINE && key != BuiltInKeys.AUTHORITY && policy.propagates(key, Boundary.MESSAGE)) {
                binding = bind(binding, policy, key, record, report);
            }
        }
        if (binding == null) {
            return ContextSnapshot.EMPTY;
        }
        ContextBinding decoded = binding;
        return ContextSnapshot.EMPTY.call(() -> decoded.call(Context::capture));
    }

    /**
     * Adds the key's value decoded from its wire header, if the header is present once and decodes (KAF-02, DESIGN D74): a repeated header
     * or a {@code null} value is reported as {@code INVALID_VALUE}; a value longer than {@code maxValueLength()} once decoded as UTF-8 with
     * replacement, in UTF-16 code units, as {@code OVERSIZED_VALUE} (WIRE-04, checked first); a value that is not well-formed UTF-8 as
     * {@code INVALID_VALUE}. None of these is decoded. A key that propagates to {@code MESSAGE} has a wire name, and a codec unless it is
     * the initiator (POL-03). Violations are reported only if {@code report} is {@code true}.
     */
    private <T> ContextBinding bind(ContextBinding binding, ContextPolicy policy, ContextKey<T> key, ConsumerRecord<?, ?> record,
            boolean report) {
        Iterator<Header> headers = record.headers().headers(policy.wireName(key).orElseThrow()).iterator();
        if (!headers.hasNext()) {
            return binding;
        }
        byte[] bytes = headers.next().value();
        if (headers.hasNext() || bytes == null) {
            return invalid(binding, Violation.Kind.INVALID_VALUE, key, report);
        }
        if (new String(bytes, UTF_8).length() > support.maxValueLength()) {
            return invalid(binding, Violation.Kind.OVERSIZED_VALUE, key, report);
        }
        String text;
        try {
            text = UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return invalid(binding, Violation.Kind.INVALID_VALUE, key, report);
        }
        T value;
        try {
            value = key == BuiltInKeys.INITIATOR
                    ? key.type().cast(Initiator.of(text))
                    : policy.codec(key).orElseThrow().decode(text);
        } catch (IllegalArgumentException e) {
            return invalid(binding, Violation.Kind.INVALID_VALUE, key, report);
        }
        return binding == null ? Context.with(key, value) : binding.with(key, value);
    }

    /**
     * Reports one {@code UNTRUSTED_HEADER} for each key of the policy whose wire header is present on the record, however often and
     * whatever its value, {@code null} included, with boundary {@code MESSAGE} and no destination (KAF-04).
     */
    private void reportUntrusted(ContextPolicy policy, ConsumerRecord<?, ?> record) {
        for (ContextKey<?> key : policy.keys()) {
            if (policy.wireName(key).filter(name -> record.headers().headers(name).iterator().hasNext()).isPresent()) {
                support.report(support.violation(Violation.Kind.UNTRUSTED_HEADER, key.name(), Boundary.MESSAGE));
            }
        }
    }

    /** Reports the key's violation if {@code report} is {@code true}, and returns {@code binding} unchanged. */
    private ContextBinding invalid(ContextBinding binding, Violation.Kind kind, ContextKey<?> key, boolean report) {
        if (report) {
            support.report(support.violation(kind, key.name(), Boundary.MESSAGE));
        }
        return binding;
    }
}
