# Contract: Executors and Test Support

Signatures only, not implementations. No parameter declared here accepts `null` (CORE-14).

## Executors (EXE) — `boundrail-context-core`

Package `io.github.boundrail.context`.

```java
public final class BoundExecutors {
    public static Executor attached(Executor delegate);
    public static Executor detached(Executor delegate);
    public static ExecutorService attached(ExecutorService delegate);
    public static ExecutorService detached(ExecutorService delegate);
    // Filter with the given policy; the overloads above filter with BuiltInKeys.defaultPolicy() (EXE-04)
    public static Executor attached(Executor delegate, ContextPolicy policy);
    public static Executor detached(Executor delegate, ContextPolicy policy);
    public static ExecutorService attached(ExecutorService delegate, ContextPolicy policy);
    public static ExecutorService detached(ExecutorService delegate, ContextPolicy policy);
}
```

## Violation listeners (DIAG-11) — `boundrail-context-spring`

Package `io.github.boundrail.context.spring`.

```java
@FunctionalInterface
public interface ViolationListener {
    void onViolation(Violation violation);
}
```

## Test support (TEST-02 – TEST-04) — `boundrail-context-test`

Package `io.github.boundrail.context.test`, as `TestInboundTrustVerifier` (TEST-01).

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(/* the library's extension */)
public @interface BoundrailTest {
}

public interface RecordedViolations {        // resolvable as a test method parameter under @BoundrailTest
    List<Violation> all();                   // in reporting order, for the running test method; IllegalStateException outside one
}

public final class ContextAssertions {
    public static SnapshotAssert assertThat(ContextSnapshot snapshot);
    public static ViolationsAssert assertThatViolations();  // IllegalStateException outside a @BoundrailTest test method
}

public final class SnapshotAssert extends AbstractAssert<SnapshotAssert, ContextSnapshot> {
    public <T> SnapshotAssert contains(ContextKey<T> key, T value);
    public SnapshotAssert doesNotContain(ContextKey<?> key);
    public SnapshotAssert isEmpty();
}

public final class ViolationsAssert extends AbstractAssert<ViolationsAssert, List<Violation>> {
    public ViolationsAssert hasSize(int expected);
    public ViolationsAssert containsKind(Violation.Kind kind);
    public ViolationsAssert isEmpty();
}
```

`@BoundrailTest` turns capture sites `on` for the class and restores the previous mode afterwards; it does not change the violation mode
(TEST-02).
