package io.github.boundrail.context.kafka;

import io.github.boundrail.context.spring.internal.AdapterSupport;

/** Provided as a bean by the Kafka auto-configuration; no public constructor (KAF-05, DESIGN D76). */
public final class KafkaContext {

    private final AdapterSupport support;
    private final boolean trustHeaders;

    KafkaContext(AdapterSupport support, boolean trustHeaders) {
        this.support = support;
        this.trustHeaders = trustHeaders;
    }
}
