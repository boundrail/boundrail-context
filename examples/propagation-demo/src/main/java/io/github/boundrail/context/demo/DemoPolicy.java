package io.github.boundrail.context.demo;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;

/** The demo's application policy: the README's {@code tenant} key, registered as a {@code ContextPolicy} bean on every internal service. */
final class DemoPolicy {

    static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);

    private DemoPolicy() {
    }

    static ContextPolicy policy() {
        return ContextPolicy.builder()
                .key(TENANT, rules -> rules
                        .codec(Codecs.string())
                        .wireName("Tenant-Id")
                        .required()
                        .loggable()
                        .propagateTo(Boundary.ATTACHED, Boundary.DETACHED, Boundary.INTERNAL_CALL, Boundary.MESSAGE))
                .build();
    }
}
