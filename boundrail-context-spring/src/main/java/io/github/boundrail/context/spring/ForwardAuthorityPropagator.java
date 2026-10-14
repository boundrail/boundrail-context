package io.github.boundrail.context.spring;

import io.github.boundrail.context.Authority;
import io.github.boundrail.context.AuthorityPropagator;
import io.github.boundrail.context.Destination;
import java.util.Objects;
import java.util.Optional;

/** The built-in {@code forward} propagator: every internal call carries the bound authority's own credential (ID-04). */
final class ForwardAuthorityPropagator implements AuthorityPropagator {

    /** {@return the authority's credential} Throws {@code NullPointerException} for a {@code null} argument (CORE-16). */
    @Override
    public Optional<String> outboundCredential(Authority authority, Destination destination) {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(destination, "destination");
        return Optional.of(authority.credential());
    }
}
