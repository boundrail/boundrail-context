# Contract: Identity

Package `io.github.boundrail.context`, module `boundrail-context-core`. `Authority` and `Initiator` are defined in
[002-core-runtime](../../002-core-runtime/contracts/core-api.md). Signatures only.

```java
public interface AuthorityPropagator {
    /** Credential to send to the destination, or empty to send none. */
    Optional<String> outboundCredential(Authority authority, Destination destination);
}
```

The built-in `forward` and `exchange` propagators (ID-04) live in `boundrail-context-spring` and are selected by
`boundrail.context.authority.propagation`; an `AuthorityPropagator` bean replaces them.
