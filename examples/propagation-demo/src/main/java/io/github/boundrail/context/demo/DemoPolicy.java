package io.github.boundrail.context.demo;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.Deadlines;

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

    /** {@return the whole milliseconds left of the current deadline; 0 if none is bound or under 1 ms is left} */
    static long budgetMillis() {
        return Deadlines.current().map(deadline -> deadline.remaining().toMillis()).orElse(0L);
    }
}
