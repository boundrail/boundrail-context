package io.github.boundrail.context;

import java.util.Objects;
import java.util.Optional;

/**
 * The classified target of an outbound call, with a service name when one was declared (DEST-01).
 *
 * @param kind whether the target is internal, external or unknown
 * @param service the declared service name, empty when classified by host
 */
public record Destination(Kind kind, Optional<String> service) {

    /** How far a destination is trusted. Adapters treat {@code UNKNOWN} exactly like {@code EXTERNAL} (DEST-03). */
    public enum Kind { INTERNAL, EXTERNAL, UNKNOWN }

    public Destination {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(service, "service");
    }
}
