# Contract: HTTP Boundaries and Trust

Signatures only, not implementations. CORE-03 and CORE-04 apply here as in [core-api.md](../../002-core-runtime/contracts/core-api.md)
(CORE-13). Except `equals(Object)`, which returns `false` for `null`, no parameter, list element, varargs element or record component declared
here accepts `null`: the call throws `NullPointerException`, and an absent `Optional` is `Optional.empty()`. `ContextContributions.put` throws
`ClassCastException` for a value that is not an instance of the key's type. Methods inherited from JDK types and application-written
implementations of these interfaces are not covered.

## Policy (POL-10) — `boundrail-context-core`

Added to `ContextPolicy` from [002-core-runtime](../../002-core-runtime/contracts/core-api.md):

```java
public final class ContextPolicy {
    public Set<ContextKey<?>> keys();  // unmodifiable; registered, merged and built-in keys
}
```

## Destinations (DEST) — `boundrail-context-core`

```java
public record Destination(Kind kind, Optional<String> service) {
    public enum Kind { INTERNAL, EXTERNAL, UNKNOWN }
}

/** Transport-neutral description of an outbound call. */
public record OutboundRequest(URI uri, Optional<Destination> declared) {}

public interface DestinationClassifier {
    Destination classify(OutboundRequest request);
}

public final class HostPatternClassifier implements DestinationClassifier {
    /** Copies both lists (DEST-02). */
    public static HostPatternClassifier of(List<String> internalHosts, List<String> externalHosts);  // IllegalArgumentException: DEST-04
}
```

## Inbound HTTP (IN) — `boundrail-context-spring`

```java
public interface InboundTrustVerifier {
    enum Trust { TRUSTED_INTERNAL, UNTRUSTED }
    Trust verify(HttpServletRequest request);
}

public interface ContextContributor {
    void contribute(Authentication authentication, ContextContributions contributions);
}

public interface ContextContributions {
    <T> void put(ContextKey<T> key, T value);  // the first contribution for a key wins (IN-05)
    void invalid(ContextKey<?> key);            // reports INVALID_VALUE (IN-07); binds nothing
}

public final class JwtClaimContributor implements ContextContributor {
    public static <T> JwtClaimContributor of(String claim, ContextKey<T> key, ContextCodec<T> codec);
}
```

## Outbound HTTP (OUT) — `boundrail-context-spring`

```java
public final class BoundrailClients {
    public static Consumer<RestClient.Builder> internal(String service);
    public static Consumer<RestClient.Builder> external();
}
```

## Violations (DIAG) — `boundrail-context-core`

```java
public record Violation(
        Kind kind, String key, Boundary boundary, Optional<Destination> destination,
        String thread, Optional<String> captureSite, Optional<String> executorEvidence) {
    public enum Kind { MISSING_REQUIRED, UNTRUSTED_HEADER, INVALID_VALUE,
                       OVERSIZED_VALUE, FORBIDDEN_HEADER, MISSING_AUDIENCE }
}

public final class ContextViolationException extends RuntimeException {
    public ContextViolationException(Violation violation);
    public Violation violation();
}
```

When several required keys are unbound on one crossing, each is reported and the exception carries the first by key name (DIAG-02).

`captureSite` and `executorEvidence` are populated from 004-async-diagnostics (DIAG-04, DIAG-05); `MISSING_AUDIENCE` is raised from 006-identity
(ID-05).

## Test support (TEST) — `boundrail-context-test`

```java
public final class TestInboundTrustVerifier implements InboundTrustVerifier {
    public TestInboundTrustVerifier();
    public static Consumer<RestClient.Builder> peer();  // adds Boundrail-Test-Peer with the per-JVM secret
}
```
