package io.github.boundrail.context.it.topology;

import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Deadlines;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.http.HttpHeaders;

/**
 * Shared probe: every service records what it observed for each request, keyed by service name. Observations are plain
 * {@code Map<String, String>} so the topology does not depend on the library API.
 */
public final class ObservedContext {

    /** Observation entry holding the {@code tenant} the handler found bound; absent if unbound. */
    public static final String TENANT = "ctx.tenant";
    /** Observation entry holding the {@code priority} the handler found bound; absent if unbound. */
    public static final String PRIORITY = "ctx.priority";
    /** Observation entry holding the remaining time of the deadline the handler found bound, in nanoseconds; absent if unbound. */
    public static final String DEADLINE_REMAINING_NANOS = "ctx.deadline-remaining-nanos";
    /** Observation entry holding the subject of the authority the handler found bound; absent if unbound. */
    public static final String AUTHORITY_SUBJECT = "ctx.authority-subject";
    /** Observation entry holding the initiator the handler found bound; absent if unbound. */
    public static final String INITIATOR = "ctx.initiator";
    /** Observation entry holding the MDC map's {@code toString()} (IT-4.6). */
    public static final String MDC = "ctx.mdc";
    /** Observation entry holding {@code Context.capture().toString()} (IT-4.6). */
    public static final String SNAPSHOT = "ctx.snapshot";
    /** Observation entry holding the consumed record's header names, sorted and joined with a comma (IT-3.8, IT-4.9). */
    public static final String KAFKA_HEADER_NAMES = "kafka.header-names";
    /** Observation entry holding the time from the consumed record's timestamp to the start of its processing, in milliseconds (IT-3.8). */
    public static final String KAFKA_DELAY_MILLIS = "kafka.delay-millis";

    private final Map<String, List<Map<String, String>>> observations = new ConcurrentHashMap<>();

    public void record(String service, Map<String, String> observation) {
        observations.computeIfAbsent(service, name -> new CopyOnWriteArrayList<>()).add(Map.copyOf(observation));
    }

    /** The observations of one service, oldest first. */
    public List<Map<String, String>> of(String service) {
        return List.copyOf(observations.getOrDefault(service, List.of()));
    }

    /**
     * What a {@code /ping} handler observed: the remaining time of the deadline it found bound, read first so that it is the time on
     * arrival, its request headers, and the {@code tenant}, {@code priority}, authority subject and initiator it found bound.
     */
    static Map<String, String> ping(HttpHeaders headers) {
        Optional<Long> remainingNanos = Deadlines.current().map(deadline -> deadline.remaining().toNanos());
        Map<String, String> observation = new HashMap<>(headers(headers));
        putBound(observation, remainingNanos);
        return observation;
    }

    /**
     * What billing's listener observed when it started processing a record: the marker (the record's value), the record's header names
     * (sorted, joined with a comma), the time from the record's timestamp to now in milliseconds, and the same bound entries as
     * {@link #ping}.
     */
    static Map<String, String> consumed(ConsumerRecord<String, String> record) {
        long delayMillis = System.currentTimeMillis() - record.timestamp();
        Optional<Long> remainingNanos = Deadlines.current().map(deadline -> deadline.remaining().toNanos());
        Map<String, String> observation = new HashMap<>();
        observation.put(EdgeApp.MARKER.toLowerCase(Locale.ROOT), record.value());
        observation.put(KAFKA_HEADER_NAMES, StreamSupport.stream(record.headers().spliterator(), false)
                .map(Header::key)
                .sorted()
                .collect(Collectors.joining(",")));
        observation.put(KAFKA_DELAY_MILLIS, Long.toString(delayMillis));
        putBound(observation, remainingNanos);
        return observation;
    }

    /** Adds the deadline's remaining time, read earlier by the caller, and the bound entries both observations share. */
    private static void putBound(Map<String, String> observation, Optional<Long> remainingNanos) {
        remainingNanos.ifPresent(nanos -> observation.put(DEADLINE_REMAINING_NANOS, nanos.toString()));
        AppKeys.TENANT.find().ifPresent(tenant -> observation.put(TENANT, tenant));
        AppKeys.PRIORITY.find().ifPresent(priority -> observation.put(PRIORITY, priority.toString()));
        BuiltInKeys.AUTHORITY.find().ifPresent(authority -> observation.put(AUTHORITY_SUBJECT, authority.subject()));
        BuiltInKeys.INITIATOR.find().ifPresent(initiator -> observation.put(INITIATOR, initiator.value()));
    }

    /** Request headers as an observation: lower-case names, multiple values joined with a comma. */
    static Map<String, String> headers(HttpHeaders headers) {
        return headers.headerSet().stream()
                .collect(Collectors.toMap(
                        header -> header.getKey().toLowerCase(Locale.ROOT),
                        header -> String.join(",", header.getValue()),
                        (first, second) -> first + "," + second));
    }
}
