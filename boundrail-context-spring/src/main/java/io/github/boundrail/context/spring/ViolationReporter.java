package io.github.boundrail.context.spring;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.Violation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.logging.Log;

/**
 * Reports violations: a WARN log entry, at most one per (kind, key, boundary) per log interval, and a count of every occurrence (DIAG-03). The
 * entry identifies the value only by key name, wire name and boundary, and never holds a value (DIAG-09).
 */
class ViolationReporter {

    /** Counts a violation. Supplied by the Micrometer configuration, so this class never names a Micrometer type. */
    @FunctionalInterface
    interface Counter {
        void increment(Violation violation);
    }

    private record LogKey(Violation.Kind kind, String key, Boundary boundary) {
    }

    private final EffectivePolicy policy;
    private final Duration logInterval;
    private final Counter counter;
    private final Log log;
    private final Clock clock;
    private final Map<LogKey, Instant> lastLogged = new ConcurrentHashMap<>();

    ViolationReporter(EffectivePolicy policy, Duration logInterval, Counter counter, Log log, Clock clock) {
        this.policy = policy;
        this.logInterval = logInterval;
        this.counter = counter;
        this.log = log;
        this.clock = clock;
    }

    void report(Violation violation) {
        counter.increment(violation);
        if (dueForLog(new LogKey(violation.kind(), violation.key(), violation.boundary()))) {
            log.warn(message(violation));
        }
    }

    /** Records the entry's time and returns {@code true} if no entry for the same key was logged within the log interval. */
    private boolean dueForLog(LogKey logKey) {
        Instant now = clock.instant();
        boolean[] due = new boolean[1];
        lastLogged.compute(logKey, (key, last) -> {
            due[0] = last == null || !now.isBefore(last.plus(logInterval));
            return due[0] ? now : last;
        });
        return due[0];
    }

    private String message(Violation violation) {
        StringBuilder message = new StringBuilder("Context violation ").append(violation.kind())
                .append(": key ").append(violation.key());
        policy.policy().keys().stream()
                .filter(key -> key.name().equals(violation.key()))
                .findFirst()
                .flatMap(key -> policy.policy().wireName(key))
                .ifPresent(wireName -> message.append(", wire name ").append(wireName));
        message.append(", boundary ").append(violation.boundary());
        violation.destination().ifPresent(destination -> {
            message.append(", destination ").append(destination.kind());
            destination.service().ifPresent(service -> message.append(' ').append(service));
        });
        return message.append(", thread ").append(violation.thread()).toString();
    }
}
