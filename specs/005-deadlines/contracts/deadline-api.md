# Contract: Deadlines

Signatures only, not implementations. CORE-03 applies here as in [core-api.md](../../002-core-runtime/contracts/core-api.md) (CORE-15): no
parameter declared here accepts `null`, and the call throws `NullPointerException`. Methods inherited from JDK types are not covered.

## Deadline exceptions (DL-12) — `boundrail-context-core`

Package `io.github.boundrail.context`. Added to `DeadlineExceededException` from
[002-core-runtime](../../002-core-runtime/contracts/core-api.md), whose constructor was package-private:

```java
public final class DeadlineExceededException extends RuntimeException {
    public DeadlineExceededException(Stage stage);                   // message "Deadline exceeded at stage <STAGE>"
    public DeadlineExceededException(Stage stage, Throwable cause);  // same message; getCause() returns cause
}
```

The Spring module adds no public types in this feature; its behaviour is configured through the properties in [spec.md](../spec.md#configuration).
