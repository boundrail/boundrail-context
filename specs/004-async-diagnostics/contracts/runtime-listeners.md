# Contract: Scope Listeners and Capture Sites

Package `io.github.boundrail.context`, module `boundrail-context-core`. Extends `ContextRuntime` from
[002-core-runtime](../../002-core-runtime/contracts/core-api.md). Signatures only.

```java
public interface ScopeListener {
    /** Called on the entering thread after {@code entered} becomes current. */
    Restorer onEnter(ContextSnapshot entered);

    interface Restorer {
        /** Called on the same thread when the scope exits, before the previous snapshot is restored. */
        void restore();
    }
}

public final class ContextRuntime {  // continued
    public enum CaptureSites { OFF, ON, SAMPLED }
    public static void addScopeListener(ScopeListener listener);
    public static void removeScopeListener(ScopeListener listener);
    public static void captureSites(CaptureSites mode, double sampleRate);
}
```
