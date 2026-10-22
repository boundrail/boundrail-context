# Contract: Kafka

Module `boundrail-context-kafka`, package `io.github.boundrail.context.kafka`. Signatures only, not implementations. CORE-03 applies here as
in [core-api.md](../../002-core-runtime/contracts/core-api.md) (CORE-17): no parameter declared here accepts `null`, and the call throws
`NullPointerException`. Methods inherited from JDK types are not covered.

## Batch listener helper (KAF-05)

`KafkaContext` is a bean, not a static helper, because the effective policy, the trust setting and violation reporting belong to one
application context (RT-09; DESIGN D76).

```java
/** Provided as a bean by the Kafka auto-configuration; no public constructor. Its Javadoc states the coverage limits (KAF-06). */
public final class KafkaContext {
    /**
     * The snapshot KAF-02 would bind for the record, under KAF-04's trust setting; reports the same violations. The snapshot is not current
     * when this returns; scope listeners may observe one enter and exit pair, because it is built in a temporary scope (KAF-05).
     */
    public ContextSnapshot forRecord(ConsumerRecord<?, ?> record);
}
```

The producer and consumer adapters (KAF-01 – KAF-04), including the `ProducerPostProcessor`, the listener advice and the error-handler and
after-rollback decorators (DESIGN D79), are not public types. The Kafka module adds no other public types in this feature beyond
the properties record `BoundrailKafkaProperties` that binds `boundrail.context.kafka.*` (KAF-04), which lives in `boundrail-context-kafka`
(DESIGN D70, D78). The types `boundrail-context-spring` exposes to it in `io.github.boundrail.context.spring.internal`, such as the
interface `AdapterSupport`, are not part of any contract. Auto-configuration classes are not part of any contract either, although Spring
Boot requires them to be public: `BoundrailKafkaAutoConfiguration` here, as `BoundrailContextAutoConfiguration` in `boundrail-context-spring`
(DESIGN D81).
