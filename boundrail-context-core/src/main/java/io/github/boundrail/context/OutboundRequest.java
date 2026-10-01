package io.github.boundrail.context;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * Transport-neutral description of an outbound call (DEST-01).
 *
 * @param uri the request URI
 * @param declared the destination declared by the client, if any
 */
public record OutboundRequest(URI uri, Optional<Destination> declared) {

    public OutboundRequest {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(declared, "declared");
    }
}
