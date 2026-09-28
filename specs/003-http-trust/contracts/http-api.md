# Contract: HTTP Boundaries and Trust

Signatures only, not implementations.

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
    public static HostPatternClassifier of(List<String> internalHosts, List<String> externalHosts);
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
    <T> void put(ContextKey<T> key, T value);
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
    public Violation violation();
}
```

`captureSite` and `executorEvidence` are populated from 004-async-diagnostics (DIAG-04, DIAG-05); `MISSING_AUDIENCE` is raised from 006-identity
(ID-05).
