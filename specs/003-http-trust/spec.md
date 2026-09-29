# Feature Specification: HTTP Boundaries and Trust

**Feature Branch**: `003-http-trust`

**Created**: 2026-09-28

**Status**: Draft

**Milestone**: M2

**Input**: Roadmap milestone [M2](../../docs/roadmap.md#m2--http-boundaries-and-trust).

API signatures: [contracts/http-api.md](contracts/http-api.md). Test topology: [001-foundation](../001-foundation/spec.md#test-topology).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Internal calls carry context, external calls carry none (Priority: P1)

A service calls other services with `RestClient` or `RestTemplate`. Context declared for internal calls travels to internal services as headers;
nothing reaches external or unknown destinations, even headers that application code set by hand.

**Why this priority**: Leaking internal context to third parties is the main failure the library prevents.

**Independent Test**: TrustBoundaryIT IT-2.1 – IT-2.4 and IT-2.9.

**Acceptance Scenarios** (TrustBoundaryIT):

1. **IT-2.1 Internal call carries tenant.** Given a public request authenticated as alice, when edge calls orders through a client declared
   `internal("orders")`, then orders observes `tenant=acme`. *(IN-04, IN-06, OUT-03, OUT-04, WIRE-01)*
2. **IT-2.2 External call carries no context.** When edge calls partner through a client declared `external()`, then partner receives none of
   `Tenant-Id`, `Priority`, `Boundrail-Initiator` or `Boundrail-Deadline-Ms`. *(OUT-04)*
3. **IT-2.3 Unknown means external.** When edge calls partner through a client with no declaration, whose host matches no pattern, then the result is
   the same as IT-2.2. *(OUT-02, OUT-03, DEST-02, DEST-03)*
4. **IT-2.4 Application-set forbidden headers are stripped.** When edge application code sets `Tenant-Id: acme` and
   `Authorization: Bearer partner-key` on a call declared external, then partner receives `Authorization: Bearer partner-key` and no `Tenant-Id`, and
   one `FORBIDDEN_HEADER` violation is reported. *(WIRE-07)*
5. **IT-2.9 Required key missing on an internal call.** Given violation mode `fail`, when edge code runs `Context.without(tenant)` and calls orders,
   then `ContextViolationException` is thrown and orders receives no request. *(POL-08, DIAG-02)*

---

### User Story 2 - Forged context is rejected at the edge (Priority: P1)

Context headers are bound only from trusted internal callers. At the edge, context comes from verified authentication. Forged, invalid or oversized
values are ignored and reported, and never fail the request.

**Why this priority**: A service that trusts a header any public client can set is the classic context-forgery bug.

**Independent Test**: TrustBoundaryIT IT-2.5 – IT-2.8.

**Acceptance Scenarios** (TrustBoundaryIT):

1. **IT-2.5 Forged tenant from a public caller is ignored.** When a public caller authenticated as alice sends `Tenant-Id: evil`, then the edge
   handler observes `tenant=acme` (from the JWT claim, bound after authentication), one `UNTRUSTED_HEADER` violation is reported, and the request
   succeeds. *(IN-02, IN-04, IN-05)*
2. **IT-2.6 Forged tenant with no claim.** When a public caller whose JWT has no `tenant` claim sends `Tenant-Id: evil`, then `tenant` is unbound in
   the edge handler. *(IN-02, IN-06)*
3. **IT-2.7 Trusted caller's header is accepted.** When edge (a trusted caller) sends `Tenant-Id: acme` to orders, then orders binds `tenant=acme`.
   *(IN-02, IN-03, TEST-01)*
4. **IT-2.8 Invalid and oversized values.** When a trusted caller sends `Priority: abc`, then orders leaves `priority` unbound and one
   `INVALID_VALUE` violation is reported. When it sends a 300-character `Tenant-Id`, then orders leaves `tenant` unbound and one `OVERSIZED_VALUE`
   violation is reported. Both requests succeed. *(WIRE-04, WIRE-05)*

---

### Edge Cases

- A header set by application code on an internal call differs from the context value: OUT-04 (application wins).
- A destination matches both an internal and an external host pattern: DEST-02 (internal patterns are checked first).
- The violation mode is `report`: DIAG-02 (the call continues without the key).

## Requirements *(mandatory)*

### Policy in Spring

**POL-07** `[M2]` In Spring, the effective policy SHALL be the built-in default merged with every `ContextPolicy` bean in the application context.
With no beans, the effective policy is the built-in default.

**POL-08** `[M2]` WHEN an adapter is about to cross a remote boundary (`INTERNAL_CALL`, `EXTERNAL_CALL`, `MESSAGE`) and a key that is required and
propagates to that boundary is unbound, the adapter SHALL raise a `MISSING_REQUIRED` violation (DIAG-02) before sending. Required-key checks SHALL NOT
be performed at in-process boundaries. Loss of context in-process surfaces at the next remote crossing.

### Destinations

**DEST-01** `[M2]` `Destination`, `OutboundRequest`, `DestinationClassifier` and `HostPatternClassifier` SHALL live in `boundrail-context-core` with
no dependency on any HTTP library, so that every outbound adapter (HTTP clients in v0.1; WebClient, Feign and gRPC later) classifies through the same
SPI.

**DEST-02** `[M2]` `HostPatternClassifier` SHALL apply, in order:

1. the request's declared destination, if present;
2. the internal host patterns;
3. the external host patterns;
4. otherwise, `UNKNOWN`.

Host patterns SHALL be exact hostnames or a leading `*.` wildcard matching one or more labels, compared case-insensitively. A host-pattern match SHALL
yield no service name.

**DEST-03** `[M2]` Every adapter SHALL treat `UNKNOWN` exactly like `EXTERNAL`.

### Wire format

**WIRE-01** `[M2]` Keys SHALL be carried in HTTP headers and Kafka record headers named by their wire names. Values SHALL be the codec's string
encoding, as UTF-8 bytes for Kafka.

**WIRE-02** `[M2]` The built-in wire formats SHALL be:

- `Boundrail-Deadline-Ms`: remaining milliseconds as a non-negative decimal integer;
- `Boundrail-Initiator`: the initiator's string value;
- authority: the standard `Authorization: Bearer <credential>` header.

**WIRE-04** `[M2]` IF an encoded value, outbound or inbound, is longer than `boundrail.context.wire.max-value-length` characters (default 256), THEN
it SHALL be neither sent nor bound, and an `OVERSIZED_VALUE` violation SHALL be reported. The operation SHALL continue.

**WIRE-05** `[M2]` IF a trusted inbound value fails to decode, THEN the key SHALL be left unbound and an `INVALID_VALUE` violation SHALL be reported.
The request SHALL continue.

**WIRE-06** `[M2]` Wire names SHALL be matched case-insensitively for HTTP and exactly for Kafka.

**WIRE-07** `[M2]` WHEN sending to an `EXTERNAL` or `UNKNOWN` destination, the adapter SHALL remove every header whose name is a policy wire name of a
key that does not propagate to `EXTERNAL_CALL`, even if application code set it. It SHALL report a `FORBIDDEN_HEADER` violation per removed header.
The `Authorization` header SHALL NOT be removed.

### Inbound HTTP

**IN-01** `[M2]` The Spring module SHALL register `ContextInboundFilter`, ordered before Spring Security's filter chain. It SHALL run the rest of the
chain inside a scope (around-style).

**IN-02** `[M2]` WHEN a request arrives, `ContextInboundFilter` SHALL ask the `InboundTrustVerifier` for a verdict. WHEN the verdict is
`TRUSTED_INTERNAL`, it SHALL decode and bind every key whose policy includes `INTERNAL_CALL` and whose wire header is present, except `AUTHORITY` and
`DEADLINE` (which DL-03 handles). WHEN the verdict is `UNTRUSTED`, it SHALL bind none of them, and SHALL report one `UNTRUSTED_HEADER` violation per
policy wire header present. Untrusted headers SHALL never fail the request.

**IN-03** `[M2]` The default verifier SHALL return `UNTRUSTED` for every request. WHERE `boundrail.context.inbound.trust=cidr`, the built-in CIDR
verifier SHALL return `TRUSTED_INTERNAL` exactly when the request's remote address is inside one of `boundrail.context.inbound.trusted-cidrs`. A bean
of type `InboundTrustVerifier` SHALL replace the built-in verifier.

**IN-04** `[M2]` WHERE Spring Security is on the classpath, the Spring module SHALL register `ContextAuthenticationFilter` after Spring Security's
authentication filters. WHEN the request is authenticated, it SHALL invoke every `ContextContributor` bean and run the rest of the chain in a nested
scope that binds the contributed values.

**IN-05** `[M2]` Contributed values SHALL bind only keys that are not already bound (trusted headers take precedence), except `AUTHORITY` (M5), which
is always bound from the authentication.

**IN-06** `[M2]` `JwtClaimContributor` SHALL decode the named claim of a JWT authentication with the given codec. IF the claim is missing or invalid,
THEN the key SHALL remain unbound, and an invalid value SHALL be reported as `INVALID_VALUE`.

### Outbound HTTP

**OUT-01** `[M2]` The Spring module SHALL add the library's `ClientHttpRequestInterceptor` to every auto-configured `RestClient.Builder` and
`RestTemplateBuilder`. The interceptor SHALL be around-style (`execution.execute`).

**OUT-02** `[M2]` WHEN a request is about to be sent, the interceptor SHALL build an `OutboundRequest` from the request URI and the client's
declaration, if any, and classify it with the effective `DestinationClassifier` (DEST-02, DEST-03).

**OUT-03** `[M2]` The Spring module SHALL provide a `HostPatternClassifier` bean configured from `boundrail.context.destinations.internal-hosts` and
`boundrail.context.destinations.external-hosts`. A bean of type `DestinationClassifier` SHALL replace it. `BoundrailClients.internal(service)` SHALL
declare `Destination(INTERNAL, service)`, and `BoundrailClients.external()` SHALL declare `Destination(EXTERNAL, empty)`, for every request made
through the client it configures.

**OUT-04** `[M2]` For `INTERNAL` destinations, the interceptor SHALL write the wire headers of bound keys that propagate to `INTERNAL_CALL`, after the
required-key check (POL-08). For `EXTERNAL` and `UNKNOWN` destinations, it SHALL write only keys that propagate to `EXTERNAL_CALL` (none by default),
and SHALL apply WIRE-07. IF application code has already set a wire header on an `INTERNAL` request, THEN the interceptor SHALL leave it unchanged, as
ID-03 does for `Authorization`.

### Violations

**DIAG-01** `[M2]` The library SHALL recognise exactly the violation kinds listed in [contracts/http-api.md](contracts/http-api.md).

**DIAG-02** `[M2]` WHEN a `MISSING_REQUIRED` violation occurs and `boundrail.context.violations.mode=fail` (default), the adapter SHALL throw
`ContextViolationException` before crossing the boundary. WHEN the mode is `report`, it SHALL report the violation and continue without the key. All
other violation kinds SHALL always be report-only.

**DIAG-03** `[M2]` Reporting a violation SHALL:

- log at WARN, rate-limited to one entry per (kind, key, boundary) per `boundrail.context.violations.log-interval` (default 1 minute);
- WHERE Micrometer is present, increment the counter `boundrail.context.violations`, tagged with `kind`, `key` and `boundary` in lowercase.

Every occurrence SHALL be counted, even when the log entry is rate-limited.

**DIAG-08** `[M2]` For a violation found on an inbound value, `boundary` SHALL be the boundary the value travelled over (`INTERNAL_CALL` for HTTP,
`MESSAGE` for Kafka), and `destination` SHALL be empty.

### Test support

**TEST-01** `[M2]` `boundrail-context-test` SHALL provide `TestInboundTrustVerifier`. It returns `TRUSTED_INTERNAL` exactly when the request carries
the header `Boundrail-Test-Peer` with a per-run secret, and SHALL provide a `RestClient` customizer that adds that header. This simulates mesh
identity in tests. It SHALL NOT be auto-configured.

### Configuration

Properties use the prefix `boundrail.context.`.

| Property | Default | Meaning |
|---|---|---|
| `violations.mode` | `fail` | `fail` or `report` (DIAG-02) |
| `violations.log-interval` | `1m` | Rate limit for violation logs (DIAG-03) |
| `inbound.trust` | `none` | `none` or `cidr`; a verifier bean overrides it (IN-03) |
| `inbound.trusted-cidrs` | `[]` | CIDRs trusted when `inbound.trust=cidr` |
| `destinations.internal-hosts` | `[]` | Host patterns classified as internal (DEST-02, OUT-03) |
| `destinations.external-hosts` | `[]` | Host patterns classified as external (DEST-02, OUT-03) |
| `wire.max-value-length` | `256` | Maximum encoded value length (WIRE-04) |

### Key Entities

- **Destination**: the classified target of an outbound call (`INTERNAL`, `EXTERNAL`, `UNKNOWN`) plus an optional service name.
- **Trust verdict**: `TRUSTED_INTERNAL` or `UNTRUSTED` for an inbound caller.
- **Violation**: a context rule broken at runtime, with only observed evidence.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: IT-2.1 – IT-2.9 are enabled and green on JDK 21 and JDK 25.
- **SC-002**: With default configuration, no context header reaches the `partner` stub in any scenario.
- **SC-003**: No untrusted, invalid or oversized header fails a request.

## Assumptions

- 002-core-runtime is complete.
- Only servlet-based Spring MVC, `RestClient` and `RestTemplate` are covered; WebFlux and WebClient are out of scope for v0.1.
