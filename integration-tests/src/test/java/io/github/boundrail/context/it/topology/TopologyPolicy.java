package io.github.boundrail.context.it.topology;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.ContextPolicy;

/** The topology table's application policy, registered as a {@code ContextPolicy} bean on every service. */
final class TopologyPolicy {

    private TopologyPolicy() {
    }

    static ContextPolicy policy() {
        return ContextPolicy.builder()
                .key(AppKeys.TENANT, rules -> rules
                        .codec(Codecs.string())
                        .wireName("Tenant-Id")
                        .required()
                        .loggable()
                        .propagateTo(Boundary.ATTACHED, Boundary.DETACHED, Boundary.INTERNAL_CALL, Boundary.MESSAGE))
                .key(AppKeys.PRIORITY, rules -> rules
                        .codec(Codecs.longValue())
                        .wireName("Priority")
                        .propagateTo(Boundary.ATTACHED, Boundary.DETACHED, Boundary.INTERNAL_CALL))
                .build();
    }
}
