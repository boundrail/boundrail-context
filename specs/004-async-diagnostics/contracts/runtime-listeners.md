# Contract: Scope Listeners and Capture Sites

Package `io.github.boundrail.context`, module `boundrail-context-core`. Extends `ContextRuntime` and `ContextSnapshot` from
[002-core-runtime](../../002-core-runtime/contracts/core-api.md). Signatures only. No parameter declared here accepts `null`
(CORE-14).

```java
public interface ScopeListener {
    /**
     * Called on the entering thread after {@code entered} becomes current. Must not return {@code null}: the operation then does not run,
     * the restorers already returned run in reverse order, and NullPointerException is thrown (RT-07).
     */
    Restorer onEnter(ContextSnapshot entered);

    interface Restorer {
        /** Called on the same thread when the scope exits, before the previous snapshot is restored. */
        void restore();
    }
}

public final class ContextRuntime {  // continued
    public enum CaptureSites { OFF, ON, SAMPLED }
    public static void addScopeListener(ScopeListener listener);     // already registered: no change (RT-07)
    public static void removeScopeListener(ScopeListener listener);  // not registered: no-op (RT-07)
    public static void captureSites(CaptureSites mode, double sampleRate);  // sampleRate NaN or outside 0.0 – 1.0: IllegalArgumentException
    public static CaptureSites captureSites();                             // current mode (RT-08)
    public static double sampleRate();                                     // current sample rate (RT-08)
}

public interface ContextSnapshot {  // continued
    Optional<String> captureSite();  // StackTraceElement.toString() form, or empty (DIAG-10)
}
```
