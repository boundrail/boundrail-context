# Feature Specification: Identity

**Feature Branch**: `006-identity`

**Created**: 2026-09-28

**Status**: Draft

**Milestone**: M5

**Input**: Roadmap milestone [M5](../../docs/roadmap.md#m5--identity).

API signatures: [contracts/identity-api.md](contracts/identity-api.md); `Authority` and `Initiator` are defined in
[002-core-runtime](../002-core-runtime/contracts/core-api.md). Test topology: [001-foundation](../001-foundation/spec.md#test-topology).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Authority from verified authentication, forwarded to internal calls (Priority: P1)

At the edge, authority and initiator come from the verified authentication. Internal calls carry the caller's credential, and an `Authorization`
header set by application code is respected. Trusted internal callers' initiator headers take precedence.

**Why this priority**: The minimum useful identity story: downstream services know who is authorised and who caused the work.

**Independent Test**: AuthorityPropagationIT IT-4.1, IT-4.2, IT-4.4 and IT-4.7.

**Acceptance Scenarios** (AuthorityPropagationIT):

1. **IT-4.1 Edge establishes authority and initiator.** When a public caller authenticated with alice's JWT calls edge, then the edge handler
   observes `authority.subject=alice` and `initiator=user:alice`. *(ID-02, ID-08)*
2. **IT-4.2 Forward mode.** Given `authority.propagation=forward`, when edge calls orders, then orders receives
   `Authorization: Bearer <alice's token>`, and observes `authority.subject=alice` and `initiator=user:alice`. *(ID-03, ID-04)*
3. **IT-4.4 No verified credential, no authority.** When a public caller without a token calls a `permitAll` edge endpoint and sends
   `Boundrail-Initiator: user:mallory`, then authority and initiator are both unbound, and an `UNTRUSTED_HEADER` violation is reported. *(ID-02,
   IN-02)*
4. **IT-4.7 Application-set Authorization is respected.** When edge application code sets `Authorization: Bearer service-token` on a call to orders,
   then orders receives `service-token`. *(ID-03)*

---

### User Story 2 - Token exchange for audience-scoped tokens (Priority: P2)

Instead of forwarding the original token, the library exchanges it (RFC 8693) for a token scoped to the destination service, and caches it.

**Why this priority**: Required where tokens are issued per target service; forwarding covers the simpler deployments first.

**Independent Test**: AuthorityPropagationIT IT-4.3 and IT-4.8.

**Acceptance Scenarios** (AuthorityPropagationIT):

1. **IT-4.3 Exchange mode.** Given `authority.propagation=exchange`, when edge calls orders twice, then orders receives a token issued by the
   token-exchange stub with audience `orders` and never alice's original token, and the stub is called exactly once. *(ID-04)*
2. **IT-4.8 Exchange without an audience.** Given `authority.propagation=exchange`, when edge calls `orders.svc.test` through a client with no
   declaration (so it is classified `INTERNAL` by the host pattern `*.svc.test`, without a service name), then no `Authorization` header is written
   and one `MISSING_AUDIENCE` violation is reported. *(ID-05)*

---

### User Story 3 - Authority never leaks (Priority: P1)

Authority never reaches external calls, detached work, messages, logs, the MDC or violation reports, while the initiator does reach detached work.

**Why this priority**: A leaked credential is a security incident; this is a guarantee, not a feature.

**Independent Test**: AuthorityPropagationIT IT-4.5 and IT-4.6.

**Acceptance Scenarios** (AuthorityPropagationIT):

1. **IT-4.5 Authority never leaves.** When edge calls partner (declared external), then partner receives no `Authorization` header. When edge runs
   work on a detached executor, then the task observes no authority and does observe `initiator=user:alice`. *(ID-06, POL-04)*
2. **IT-4.6 No credential in any output.** Across all IT-4 scenarios, the captured logs, the MDC, violation reports and every `toString()` of a
   snapshot never contain alice's token string. *(ID-01, ID-06, LOG-02)*

---

### Edge Cases

- Two tokens for the same subject with different scopes: ID-04 caches per source credential, so they never share an exchanged token.
- `@Async` work calling internal services sends no `Authorization` header: documented (ID-09).

## Requirements *(mandatory)*

**ID-02** `[M5]` `AUTHORITY` SHALL be bound only by `ContextAuthenticationFilter` (IN-04), from a verified Spring Security `Authentication` that
exposes a bearer credential. No adapter SHALL bind `AUTHORITY` from an HTTP context header or a Kafka header.

**ID-03** `[M5]` WHEN sending an `INTERNAL_CALL` with `AUTHORITY` bound, the adapter SHALL obtain a credential from the `AuthorityPropagator` for the
destination and set `Authorization: Bearer <credential>`. IF the request already has an `Authorization` header set by application code, THEN the
adapter SHALL leave it unchanged.

**ID-04** `[M5]` The library SHALL provide two propagators, selected by `boundrail.context.authority.propagation`:

- `forward`, the default, which returns the original credential;
- `exchange`, which performs an RFC 8693 token exchange against `boundrail.context.authority.exchange.token-uri` with `audience` equal to the
  destination's service name, authenticating with the configured client credentials. It caches the exchanged token per (hash of the source credential,
  audience) until 30 seconds before the exchanged token expires, and never beyond the source credential's expiry when the authentication exposes it.
  Cache keys SHALL NOT contain the credential in clear.

A bean of type `AuthorityPropagator` SHALL replace the built-in selection.

**ID-05** `[M5]` IF the `exchange` propagator is active and the destination has no service name, THEN no `Authorization` header SHALL be written and a
`MISSING_AUDIENCE` violation SHALL be reported.

**ID-06** `[M5]` `AUTHORITY` SHALL NOT be written to `EXTERNAL_CALL`, `MESSAGE`, MDC, logs or violation reports, and SHALL be absent from snapshots
filtered for `DETACHED`.

**ID-08** `[M5]` WHEN a request is authenticated and `INITIATOR` is not already bound from a trusted header, the library SHALL bind
`INITIATOR = "user:" + authentication.getName()`. A trusted inbound `Boundrail-Initiator` header SHALL take precedence (DESIGN D27).

**ID-09** `[M5]` The documentation SHALL state that detached work, including `@Async` methods on executors in the default detached mode, carries no
authority, so its calls to internal services send no `Authorization` header. It SHALL recommend an attached executor, or a service credential obtained
by the task itself.

### Configuration

Properties use the prefix `boundrail.context.`.

| Property | Default | Meaning |
|---|---|---|
| `authority.propagation` | `forward` | `forward` or `exchange`; a propagator bean overrides it (ID-04) |
| `authority.exchange.token-uri` | unset | RFC 8693 token endpoint (ID-04) |
| `authority.exchange.client-id` | unset | Client credentials for the exchange (ID-04) |
| `authority.exchange.client-secret` | unset | Client credentials for the exchange (ID-04) |

### Key Entities

- **Authority**: subject plus opaque bearer credential; sensitive, redacted in every string form.
- **Initiator**: plain audit identifier (`user:`, `service:`, `system:`), flows everywhere internal.
- **AuthorityPropagator**: decides which credential, if any, goes to a destination.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: IT-4.1 – IT-4.8 are enabled and green on JDK 21 and JDK 25.
- **SC-002**: alice's token string appears in no log line, MDC entry, violation report or snapshot `toString()` across the suite (IT-4.6).
- **SC-003**: In exchange mode, repeated calls for the same credential and audience hit the token endpoint once until the cached token nears expiry.

## Assumptions

- 003-http-trust and 004-async-diagnostics are complete (authentication filter, detached executors, MDC bridge).
- The token-exchange client is minimal: client credentials, audience and caching only.
