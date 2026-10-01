# Contract: Core API

Package `io.github.boundrail.context`, module `boundrail-context-core`. Signatures only, not implementations. Except `equals(Object)`, which
returns `false` for `null`, no parameter declared here accepts `null`, and no varargs parameter accepts a `null` element: the call throws
`NullPointerException` (CORE-03). Methods inherited from JDK types and application-written implementations of these interfaces are not covered.

## Keys, bindings and snapshots (CORE)

```java
public final class ContextKey<T> {
    public static <T> ContextKey<T> of(String name, Class<T> type);
    public static <T> ContextKey<T> of(String name, Class<T> type, T defaultValue);
    public String name();
    public Class<T> type();
    public Optional<T> defaultValue();
    public T get();             // bound value, else default, else throws MissingContextException
    public Optional<T> find();  // bound value, else default, else empty
    public boolean isBound();   // bound in the current snapshot (defaults do not count)
}

public final class Context {
    public static ContextSnapshot capture();
    public static <T> ContextBinding with(ContextKey<T> key, T value);
    public static ContextBinding without(ContextKey<?> key);
}

public final class ContextBinding {           // immutable; each call returns a new binding
    public <T> ContextBinding with(ContextKey<T> key, T value);
    public ContextBinding without(ContextKey<?> key);
    public void run(Runnable op);
    public <R, X extends Throwable> R call(ContextCallable<R, X> op) throws X;
}

@FunctionalInterface
public interface ContextCallable<R, X extends Throwable> {
    R call() throws X;
}

public interface ContextSnapshot {
    ContextSnapshot EMPTY = /* implementation */;
    <T> Optional<T> find(ContextKey<T> key);  // bound values only, no defaults
    Set<ContextKey<?>> keys();
    boolean isEmpty();
    void run(Runnable op);
    <R, X extends Throwable> R call(ContextCallable<R, X> op) throws X;
    Runnable wrap(Runnable op);
    <R> Callable<R> wrap(Callable<R> op);
}

public final class MissingContextException extends RuntimeException {
    public ContextKey<?> key();
}
```

## Runtime (RT)

```java
public final class ContextRuntime {
    public enum Backend { THREAD_LOCAL, SCOPED_VALUE }
    public static Backend backend();
}
```

Scope listeners and capture-site configuration are added to `ContextRuntime` in
[004-async-diagnostics](../../004-async-diagnostics/contracts/runtime-listeners.md).

## Policy and codecs (POL, WIRE-03)

```java
public enum Boundary { ATTACHED, DETACHED, INTERNAL_CALL, EXTERNAL_CALL, MESSAGE }

public interface ContextCodec<T> {
    String encode(T value);
    T decode(String text);  // throws IllegalArgumentException on invalid input
}

public final class Codecs {                    // each method returns the same instance on every call
    public static ContextCodec<String> string();
    public static ContextCodec<Long> longValue();
    public static ContextCodec<UUID> uuid();
    public static ContextCodec<Locale> locale();  // BCP 47 language tag
}

public final class ContextPolicy {
    public static Builder builder();
    public boolean propagates(ContextKey<?> key, Boundary boundary);
    public boolean isRequired(ContextKey<?> key);
    public boolean isLoggable(ContextKey<?> key);
    public Optional<String> wireName(ContextKey<?> key);
    public <T> Optional<ContextCodec<T>> codec(ContextKey<T> key);
    public ContextSnapshot filter(ContextSnapshot snapshot, Boundary boundary);
    public ContextPolicy merge(ContextPolicy other);  // fails on conflicts

    public interface Builder {
        <T> Builder key(ContextKey<T> key, Consumer<KeyRules<T>> rules);
        ContextPolicy build();
    }
    public interface KeyRules<T> {
        KeyRules<T> codec(ContextCodec<T> codec);
        KeyRules<T> wireName(String name);
        KeyRules<T> required();
        KeyRules<T> loggable();
        KeyRules<T> propagateTo(Boundary... boundaries);
    }
}

public final class BuiltInKeys {
    public static final ContextKey<Deadline>  DEADLINE;   // "boundrail.deadline"
    public static final ContextKey<Authority> AUTHORITY;  // "boundrail.authority"
    public static final ContextKey<Initiator> INITIATOR;  // "boundrail.initiator"
    public static ContextPolicy defaultPolicy();
}
```

## Built-in values (DL, ID)

```java
public final class Deadline {
    public static Deadline after(Duration budget);  // budget capped at Duration.ofNanos(Long.MAX_VALUE / 2)
    public Duration remaining();               // never negative, never above the cap
    public boolean isExpired();
    public void check();                        // throws DeadlineExceededException if expired
    public Deadline earliest(Deadline other);   // the one that expires first
    public Deadline within(Duration max);       // earliest(Deadline.after(max))
}

public final class Deadlines {
    public static Optional<Deadline> current();
}

public final class DeadlineExceededException extends RuntimeException {
    public Stage stage();
    public enum Stage { INBOUND, BEFORE_SEND, TIMEOUT, EXECUTOR, APPLICATION }
}

public final class Authority {
    public static Authority of(String subject, String credential);
    public String subject();
    public String credential();
    @Override public String toString();  // "Authority[subject=alice, credential=<redacted>]"
}

public final class Initiator {
    public static Initiator of(String value);  // e.g. "user:alice", "service:billing", "system:scheduled:x"
    public String value();
    @Override public boolean equals(Object other);  // by value
    @Override public int hashCode();
    @Override public String toString();  // the value itself
}
```
