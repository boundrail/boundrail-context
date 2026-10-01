package io.github.boundrail.context;

import java.util.Objects;
import java.util.Optional;

/**
 * Evidence of a context rule broken at a boundary. It never holds the value it concerns (DIAG-01, DIAG-09).
 *
 * @param kind which rule was broken
 * @param key the name of the context key concerned
 * @param boundary the boundary where it was detected
 * @param destination the classified destination of an outbound call, empty otherwise
 * @param thread the name of the thread that detected it
 * @param captureSite where the context was captured, when known
 * @param executorEvidence the executor involved, when known
 */
public record Violation(
        Kind kind, String key, Boundary boundary, Optional<Destination> destination,
        String thread, Optional<String> captureSite, Optional<String> executorEvidence) {

    /** The violation kinds the library recognises (DIAG-01). */
    public enum Kind { MISSING_REQUIRED, UNTRUSTED_HEADER, INVALID_VALUE,
                       OVERSIZED_VALUE, FORBIDDEN_HEADER, MISSING_AUDIENCE }

    public Violation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(captureSite, "captureSite");
        Objects.requireNonNull(executorEvidence, "executorEvidence");
    }
}
