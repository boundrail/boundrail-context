# Contract: Kafka

Module `boundrail-context-kafka`. Signatures only, not implementations.

```java
public final class KafkaContext {
    public static ContextSnapshot forRecord(ConsumerRecord<?, ?> record);
}
```
