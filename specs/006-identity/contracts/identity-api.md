# Contract: Identity

Signatures only, not implementations. CORE-03 applies here as in [core-api.md](../../002-core-runtime/contracts/core-api.md) (CORE-16): no
parameter declared here accepts `null` in the library's own classes and implementations, and the call throws `NullPointerException`. Methods
inherited from JDK types and application-written implementations of `AuthorityPropagator` are not covered.

## Authority expiry (ID-10) — `boundrail-context-core`

Package `io.github.boundrail.context`. Added to `Authority` from [002-core-runtime](../../002-core-runtime/contracts/core-api.md):

```java
public final class Authority {
    public static Authority of(String subject, String credential, Instant expiresAt);
    public Optional<Instant> expiresAt();  // empty for Authority.of(subject, credential)
}
```

## Authority propagation (ID-03, ID-04) — `boundrail-context-core`

Package `io.github.boundrail.context`. `Destination` is defined in [003-http-trust](../../003-http-trust/contracts/http-api.md).

```java
public interface AuthorityPropagator {
    /** Credential to send to the destination, or empty to send none. */
    Optional<String> outboundCredential(Authority authority, Destination destination);
}
```

The built-in `forward` and `exchange` propagators (ID-04, ID-11) live in `boundrail-context-spring` and are selected by
`boundrail.context.authority.propagation`; an `AuthorityPropagator` bean replaces them. They are not public types.

## Token exchange failures (ID-12) — `boundrail-context-spring`

Package `io.github.boundrail.context.spring`.

```java
/** The token endpoint could not be reached or gave no valid response; the message holds no token, secret or response body. */
public final class TokenExchangeException extends RuntimeException {
}
```

It has no public constructor; only the library throws it.

## Contributions (ID-02) — `boundrail-context-spring`

`ContextContributions.put` from [003-http-trust](../../003-http-trust/contracts/http-api.md) keeps its signature, and throws
`IllegalArgumentException` when its key is `BuiltInKeys.AUTHORITY`.

The Spring module adds no other public types in this feature beyond the `BoundrailContextProperties.Authority` properties record that binds
the properties in [spec.md](../spec.md#configuration); its `toString()` never contains the client secret (ID-06).
