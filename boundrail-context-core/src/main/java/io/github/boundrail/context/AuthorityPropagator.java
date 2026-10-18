package io.github.boundrail.context;

import java.util.Optional;

/**
 * Decides which credential, if any, an internal call carries for the bound authority (ID-03, ID-04). The library's outbound adapters call
 * it only for {@code INTERNAL} destinations with {@code AUTHORITY} bound and no {@code Authorization} header already set, and send the
 * result as {@code Authorization: Bearer <credential>}; external and unknown destinations never receive it.
 */
public interface AuthorityPropagator {

    /** Credential to send to the destination, or empty to send none. */
    Optional<String> outboundCredential(Authority authority, Destination destination);
}
