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
    public enum Kind {
        /** A required key is unbound at a remote boundary it propagates to (POL-08). */
        MISSING_REQUIRED,
        /** A policy wire header arrived where headers are not trusted, so it was not bound (IN-02, KAF-04). */
        UNTRUSTED_HEADER,
        /** A value is not valid, or its wire header was repeated, so it was not bound or sent. */
        INVALID_VALUE,
        /** A wire value is longer than the configured maximum, so it was not bound or sent (WIRE-04). */
        OVERSIZED_VALUE,
        /**
         * A call to an external or unknown destination carried the wire header of a key that does not propagate to
         * {@code EXTERNAL_CALL}, so it was removed (WIRE-07).
         */
        FORBIDDEN_HEADER,
        /** A token exchange has no audience, because the destination has no service name, so no credential was sent (ID-05). */
        MISSING_AUDIENCE
    }

    /** Creates a violation; every component must be non-null. */
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
