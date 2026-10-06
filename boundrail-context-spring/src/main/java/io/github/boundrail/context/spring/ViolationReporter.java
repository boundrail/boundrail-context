package io.github.boundrail.context.spring;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.Destination;
import io.github.boundrail.context.Violation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.apache.commons.logging.Log;

/**
 * Reports violations: a WARN log entry, at most one per (kind, key, boundary) per log interval, and a count of every occurrence (DIAG-03). The
 * entry identifies the value only by key name, wire name and boundary, and never holds a value (DIAG-09). Every violation is then passed to
 * {@link ViolationListener} beans defined in the reporting context, not in its ancestors, in bean order (DIAG-11).
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
    private final Supplier<Stream<ViolationListener>> listeners;
    private final Function<String, Optional<String>> executorEvidence;
    private final Log log;
    private final Clock clock;
    private final Map<LogKey, Instant> lastLogged = new ConcurrentHashMap<>();

    /**
     * Creates a reporter.
     *
     * @param listeners the listener beans defined in the reporting context, not in its ancestors, in bean order (DIAG-11)
     * @param executorEvidence the executor evidence for a thread name, from the context's executor table (DIAG-04)
     */
    ViolationReporter(EffectivePolicy policy, Duration logInterval, Counter counter, Supplier<Stream<ViolationListener>> listeners,
            Function<String, Optional<String>> executorEvidence, Log log, Clock clock) {
        this.policy = policy;
        this.logInterval = logInterval;
        this.counter = counter;
        this.listeners = listeners;
        this.executorEvidence = executorEvidence;
        this.log = log;
        this.clock = clock;
    }

    /**
     * Builds a violation with the observed evidence: the current thread's name, the capture site of the current snapshot, if one was
     * recorded, and the executor bean the thread belongs to, if any (DIAG-04). Every adapter builds its violations through this method.
     */
    Violation violation(Violation.Kind kind, String key, Boundary boundary, Optional<Destination> destination) {
        String thread = Thread.currentThread().getName();
        return new Violation(kind, key, boundary, destination, thread, Context.capture().captureSite(), executorEvidence.apply(thread));
    }

    void report(Violation violation) {
        counter.increment(violation);
        if (dueForLog(new LogKey(violation.kind(), violation.key(), violation.boundary()))) {
            log.warn(message(violation));
        }
        listeners.get().forEach(listener -> notify(listener, violation));
    }

    /** Passes the violation to one listener; an exception it throws is logged at WARN and the next listener still runs (DIAG-11). */
    private void notify(ViolationListener listener, Violation violation) {
        try {
            listener.onViolation(violation);
        } catch (RuntimeException e) {
            log.warn("Violation listener " + listener.getClass().getName() + " failed", e);
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
        message.append(", thread ").append(violation.thread());
        violation.captureSite().ifPresent(site -> message.append(", capture site ").append(site));
        violation.executorEvidence().ifPresent(evidence -> message.append(", executor ").append(evidence));
        return message.toString();
    }
}
