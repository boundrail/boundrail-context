# Contract: Executors and Test Support

Signatures only, not implementations.

## Executors (EXE) — `boundrail-context-core`

```java
public final class BoundExecutors {
    public static Executor attached(Executor delegate);
    public static Executor detached(Executor delegate);
    public static ExecutorService attached(ExecutorService delegate);
    public static ExecutorService detached(ExecutorService delegate);
}
```

## Test support (TEST-02, TEST-03) — `boundrail-context-test`

- `@BoundrailTest`: JUnit 5 extension annotation; violation mode `fail`, capture sites `on`, recorded violations exposed to the test.
- `ContextAssertions.assertThat(ContextSnapshot)`: `contains(key, value)`, `doesNotContain(key)`, `isEmpty()`.
- `ContextAssertions.assertThatViolations()`: `hasSize(int)`, `containsKind(Violation.Kind)`, `isEmpty()`.
