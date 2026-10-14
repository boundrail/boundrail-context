# Feature Specification: Identity

**Feature Branch**: `006-identity`

**Created**: 2026-09-28

**Status**: Ready for planning

**Milestone**: M5

**Input**: Roadmap milestone [M5](../../docs/roadmap.md#m5--identity).

API signatures: [contracts/identity-api.md](contracts/identity-api.md); `Authority`, `Initiator` and `BuiltInKeys` are defined in
[002-core-runtime](../002-core-runtime/contracts/core-api.md), and `Destination`, `ContextContributions` and `Violation` in
[003-http-trust](../003-http-trust/contracts/http-api.md); this feature adds an expiry to `Authority`, the `AuthorityPropagator` interface and
`TokenExchangeException`. Test topology: [001-foundation](../001-foundation/spec.md#test-topology).

## User Scenarios & Testing *(mandatory)*

In AuthorityPropagationIT, a "public request authenticated as alice" is sent by the test without the test peer header and with
`Authorization: Bearer <alice's token>`, the token `TestJwts.alice()` issues ("alice's token"). Only edge runs Spring Security, so only edge
binds an authority (ID-02); orders observes the initiator from edge's trusted `Boundrail-Initiator` header. The shared edge uses the default
`authority.propagation=forward`. "The exchange edge" is an extra edge instance that the suite starts with `Topology.Running.startEdge(...)`
and the properties `authority.propagation=exchange`, `authority.exchange.token-uri` set to the token-exchange stub's `/token` endpoint, and a
client id and secret. The token-exchange stub answers each exchange with status 200, a new access token that names the requested audience,
and `expires_in` of one hour, and counts the requests it receives. Every service records, to `ObservedContext`, the headers it receives and
the bound authority's subject and initiator when a request arrives at its handler.

### User Story 1 - Authority from verified authentication, forwarded to internal calls (Priority: P1)

At the edge, authority and initiator come from the verified authentication. Internal calls carry the caller's credential, and an `Authorization`
header set by application code is respected. Trusted internal callers' initiator headers take precedence.

**Why this priority**: The minimum useful identity story: downstream services know who is authorised and who caused the work.

**Independent Test**: AuthorityPropagationIT IT-4.1, IT-4.2, IT-4.4 and IT-4.7.

**Acceptance Scenarios** (AuthorityPropagationIT):

1. **IT-4.1 Edge establishes authority and initiator.** When a public request authenticated as alice reaches an edge handler, then the handler
   observes `authority.subject=alice` and `initiator=user:alice`. *(ID-02, ID-08)*
2. **IT-4.2 Forward mode.** Given a public request authenticated as alice and `authority.propagation=forward`, when edge calls orders through
   its `internal("orders")` client, then orders receives `Authorization: Bearer <alice's token>` and observes `initiator=user:alice`.
   *(ID-03, ID-04)*
3. **IT-4.4 No verified credential, no authority.** When a public caller without a token calls an edge endpoint that permits unauthenticated
   requests and sends `Boundrail-Initiator: user:mallory`, then the handler observes neither an authority nor an initiator, and exactly one
   `UNTRUSTED_HEADER` violation is reported, for the key `boundrail.initiator`. *(ID-02, ID-08, IN-02)*
4. **IT-4.7 Application-set Authorization is respected.** Given a public request authenticated as alice, when edge application code sets
   `Authorization: Bearer service-token` on a call through its `internal("orders")` client, then orders receives exactly
   `Authorization: Bearer service-token`. *(ID-03)*

---

### User Story 2 - Token exchange for audience-scoped tokens (Priority: P2)

Instead of forwarding the original token, the library exchanges it (RFC 8693) for a token scoped to the destination service, and caches it.

**Why this priority**: Required where tokens are issued per target service; forwarding covers the simpler deployments first.

**Independent Test**: AuthorityPropagationIT IT-4.3 and IT-4.8.

**Acceptance Scenarios** (AuthorityPropagationIT):

1. **IT-4.3 Exchange mode.** Given a public request authenticated as alice to the exchange edge, when edge calls orders twice through its
   `internal("orders")` client, then orders receives, both times, `Authorization: Bearer` followed by the token the stub issued for the
   audience `orders`, never alice's token, and the stub receives exactly one request. *(ID-03, ID-04, ID-11)*
2. **IT-4.8 Exchange without an audience.** Given a public request authenticated as alice to the exchange edge, when edge calls
   `orders.svc.test` through a client with no declaration (so it is classified `INTERNAL` by the host pattern `*.svc.test`, without a service
   name), then orders receives the request without an `Authorization` header, the stub receives no request, and exactly one `MISSING_AUDIENCE`
   violation is reported. *(ID-05)*

---

### User Story 3 - Authority never leaks (Priority: P1)

Authority never reaches external calls, detached work, messages, logs, the MDC or violation reports, while the initiator does reach detached work.

**Why this priority**: A leaked credential is a security incident; this is a guarantee, not a feature.

**Independent Test**: AuthorityPropagationIT IT-4.5 and IT-4.6.

**Acceptance Scenarios** (AuthorityPropagationIT):

1. **IT-4.5 Authority never leaves.** Given a public request authenticated as alice, when edge calls partner through its `external()` client,
   then partner receives no `Authorization` header. When edge runs work on `taskExecutor` (detached), then the task observes no authority and
   does observe `initiator=user:alice`. *(ID-03, ID-06, POL-04)*
2. **IT-4.6 No credential in any output.** Across all IT-4 scenarios, the captured logs, the MDC, violation reports and every `toString()` of a
   snapshot never contain alice's token string. *(ID-01, ID-06, LOG-02)*

---

### Edge Cases

- Two tokens for the same subject with different scopes: ID-11 caches per source credential, so they never share an exchanged token.
- `@Async` work calling internal services sends no `Authorization` header: ID-09 (stated in the `BoundExecutors` Javadoc).
- An authenticated request whose `Authentication` is not an `AbstractOAuth2TokenAuthenticationToken`, such as form login or HTTP Basic:
  ID-02 (no authority); ID-08 (the initiator is still derived).
- An anonymous or unauthenticated request: ID-02, ID-08 (neither is bound; IN-04 defines authenticated).
- An internal service without Spring Security, such as orders in the test topology, receives `Authorization`: ID-02 (it binds no authority,
  so its own internal calls carry no `Authorization` header).
- Application code binds `AUTHORITY` itself with `Context.with`, for example a task with a service credential: ID-02 (allowed; ID-03 sends
  it to internal calls).
- A contributor puts `AUTHORITY`: ID-02 (`IllegalArgumentException` at the `put` call, even with a value of the wrong type through a raw
  key). A contributor puts `INITIATOR`: ID-08 (it wins over
  the derived `user:` initiator, and a trusted header wins over both).
- A trusted caller sends `Boundrail-Initiator` to an authenticated request: ID-08 (the header's initiator is kept).
- `authentication.getName()` is longer than 251 characters: ID-08 (`INITIATOR` unbound, one `INVALID_VALUE`; the request continues).
- A handler's MVC `Callable` or `DeferredResult` (IN-09, EXE-09): ID-02, ID-08 (the authority and initiator are in the stored snapshot, and a
  `Callable` runs with them, filtered for `ATTACHED`).
- A `@Scheduled` job (EXE-06): no authority; its initiator names the job.
- Application code sets `Authorization` on an internal call: ID-03 (unchanged; the propagator is not called, so no exchange and no
  `MISSING_AUDIENCE`). On an external or unknown call: WIRE-07 (kept). A name present with no values: ID-03 (treated as unset, as OUT-04
  defines).
- A credential longer than `wire.max-value-length`, such as any RS256 JWT with the default of 256: ID-03 (sent; WIRE-04 does not apply,
  DESIGN D60). A credential with CR, LF or another character not allowed in a header: ID-03 (not written, one `INVALID_VALUE`; WIRE-08).
- A custom `AuthorityPropagator` returns empty: ID-03 (no `Authorization` header, nothing reported).
- An internal call made while a required key is unbound, in `fail` mode: POL-08, ID-03 (the call fails before the propagator is called).
- An internal call made after the deadline has expired: DL-06, ID-03 (`BEFORE_SEND` is thrown before the propagator is called).
- The token endpoint is unreachable, answers with a status other than 200, or with a body that is not a valid response: ID-12
  (`TokenExchangeException`; the call is not sent).
- The token endpoint is slower than the remaining deadline: ID-12 (`DeadlineExceededException`, stage `TIMEOUT`, counted; its cause is
  the `HttpTimeoutException`). The deadline runs out between DL-06's check and the exchange: ID-12 (`TIMEOUT`, counted; no exchange request
  and no call). The calling thread is interrupted during the exchange: ID-12 (`TokenExchangeException`; the library restores the interrupt
  status, which the JDK client clears). Slower than 10s with no deadline bound: ID-12 (`TokenExchangeException`).
- `expires_in` is `3600.0` or `1e3`: ID-11 (valid). It is `"3600"`, `null`, `1.5`, `-1` or NaN: ID-11 (invalid response, ID-12). It is too
  large for a `long`, or infinite: ID-11 (valid; the cache lifetime is capped). The response has an unexpected `Content-Type`: ID-11 (not
  checked).
- A response body with trailing content after the JSON object, a malformed escape, or an `access_token` that is JSON `null`: ID-11 (invalid
  response, ID-12; Jackson 3 parses strictly). The parse error is neither the cause nor part of the message of the `TokenExchangeException`
  (ID-12).
- The exchange response has no `expires_in`: ID-11 (the token is used for that call and not cached). An `expires_in` of 30 seconds or less:
  ID-11 (not cached). An authority with no expiry: ID-11 (only the exchanged token's expiry limits the cache).
- More than 10,000 cached exchanged tokens: ID-11 (the oldest entry is evicted). Two concurrent calls with the same credential and audience
  and nothing cached: ID-11 (both may exchange).
- Several `AuthorityPropagator` beans: ID-04 (the `@Primary` one; with none primary, startup fails).
- `authority.propagation` is `EXCHANGE`, blank or `bogus`: ID-04 (relaxed binding means `exchange`; blank means `forward`; `bogus` fails
  startup, even under lazy initialization).
- `authority.propagation=exchange` with a missing, blank, relative, hostless or non-HTTP `authority.exchange.token-uri`, or a missing or
  blank client id or secret, or without Jackson 3 on the classpath: ID-11 (startup fails, even under lazy initialization); with an
  `AuthorityPropagator` bean, the `authority.exchange.*` properties are not validated.
- A `null` argument passed to the M5 contract: CORE-16.

## Requirements *(mandatory)*

### API conventions

**CORE-16** `[M5]` CORE-03 SHALL apply to the types and methods declared in [contracts/identity-api.md](contracts/identity-api.md) as it does to
`contracts/core-api.md`, with the same exceptions: a `null` argument passed to `Authority.of` or to the library's own `AuthorityPropagator`
implementations (ID-04) SHALL throw `NullPointerException` at that call. Implementations written by applications are not covered. Verified by
unit tests.

### Authority and initiator at the edge

**ID-02** `[M5]` No adapter other than `ContextAuthenticationFilter` (IN-04) SHALL bind `AUTHORITY`, and it SHALL bind it only from a verified Spring
Security `Authentication`. No adapter SHALL bind `AUTHORITY` from an HTTP context header or a Kafka header. Application code MAY bind `AUTHORITY`
itself (DESIGN D58). WHEN the request is authenticated, as IN-04 defines, and its `Authentication` is an `AbstractOAuth2TokenAuthenticationToken`
(such as `JwtAuthenticationToken` or `BearerTokenAuthentication`), the filter SHALL bind `AUTHORITY = Authority.of(authentication.getName(),
token.getTokenValue(), token.getExpiresAt())`, where `token` is `getToken()`, or `Authority.of(authentication.getName(), token.getTokenValue())` when
`getExpiresAt()` is `null` (ID-10). It SHALL bind it in the nested scope in which it binds the contributed values (IN-04), so the snapshot stored for
`ASYNC` dispatches (IN-09) holds it. As the exception to IN-05, `AUTHORITY` SHALL be bound from the authentication whether or not it is already
bound. `ContextContributions.put` SHALL throw `IllegalArgumentException` when its key is `AUTHORITY`, after its `null` checks (CORE-13) and before
its type check (CORE-04), so a value of the wrong type passed through a raw key also gives `IllegalArgumentException` (DESIGN D64). An exception a
contributor does not catch, such as that one, propagates from the filter unchanged and the rest of the chain does not run, as for any contributor
error. An authentication whose name is `null` or empty, such as a JWT with no `sub`, still binds the authority with an empty subject, so its
credential is forwarded; ID-08 reports the derived initiator as invalid (DESIGN D66). Verified by IT-4.1, IT-4.4 and unit tests.

**ID-08** `[M5]` WHEN a request is authenticated, as IN-04 defines, and `INITIATOR` is neither already bound, for example from a trusted
`Boundrail-Initiator` header, nor contributed by a `ContextContributor` (IN-05), the library SHALL bind `INITIATOR = "user:" +
authentication.getName()`, in the scope of ID-02. A trusted inbound `Boundrail-Initiator` header SHALL take precedence over a contribution, and a
contribution over the derived value (DESIGN D27, D59). IF `authentication.getName()` is `null` or empty, or the derived value is not a valid
initiator (ID-07), THEN the filter SHALL leave `INITIATOR` unbound and SHALL report one `INVALID_VALUE` violation for `boundrail.initiator`, with the
boundary and destination DIAG-08 defines, and the request SHALL continue. Verified by IT-4.1, IT-4.4, IT-4.5 and unit tests.

**ID-10** `[M5]` `Authority` SHALL have the factory `of(String subject, String credential, Instant expiresAt)` and the accessor
`Optional<Instant> expiresAt()` declared in [contracts/identity-api.md](contracts/identity-api.md). `expiresAt()` SHALL return the given
instant, and SHALL be empty for an authority made with `of(subject, credential)`. ID-01 still applies: `toString()` SHALL NOT contain the
credential, and `equals` and `hashCode` stay identity-based (DESIGN D58). `toString()` SHALL NOT show the expiry: its format stays the one
the core contract defines (DESIGN D64). Verified by unit tests.

### Outbound HTTP

**ID-03** `[M5]` WHEN the library's interceptor (OUT-01) sends a request to an `INTERNAL` destination with `AUTHORITY` bound, it SHALL, after the
required-key check (POL-08), call the effective `AuthorityPropagator` with the bound authority and the destination. IF the propagator returns a
credential, THEN the interceptor SHALL set `Authorization: Bearer <credential>`; IF it returns empty, THEN no `Authorization` header SHALL be
written and nothing SHALL be reported. IF application code has already set an `Authorization` header on the request, THEN the interceptor SHALL
leave it unchanged and SHALL NOT call the propagator. A header counts as already set as OUT-04 defines: it has at least one value, names are
compared as WIRE-06 defines, the empty string `""` is a value, and a name present with no values is unset. The interceptor SHALL NOT call the
propagator, nor write `Authorization`, for an `EXTERNAL` or `UNKNOWN` destination, or with no `AUTHORITY` bound; an `Authorization` header
application code set on an external or unknown request is kept (WIRE-07). WIRE-04's length limit SHALL NOT apply to the `Authorization` value
the interceptor writes: the credential comes from verified authentication or from the token endpoint, not from the wire (DESIGN D60). WIRE-08
SHALL apply to it: IF `Bearer <credential>` is not a valid field value, THEN the interceptor SHALL NOT write it, SHALL report one
`INVALID_VALUE` violation for `boundrail.authority` with the boundary `INTERNAL_CALL` and the destination, and the call SHALL continue.
Verified by IT-4.2, IT-4.3, IT-4.5, IT-4.7 and unit tests.

**ID-04** `[M5]` The library SHALL provide two propagators, selected by `boundrail.context.authority.propagation`:

- `forward`, the default, which returns the original credential;
- `exchange`, which performs an RFC 8693 token exchange against `boundrail.context.authority.exchange.token-uri` with `audience` equal to the
  destination's service name, authenticating with the configured client credentials (ID-11, ID-12). It caches the exchanged token per (hash of
  the source credential, audience) until 30 seconds before the exchanged token expires, and never beyond the source credential's expiry when the
  authority has one (ID-10). Cache keys SHALL NOT contain the credential in clear.

A bean of type `AuthorityPropagator` SHALL replace the built-in selection. IF the application context has more than one `AuthorityPropagator`
bean, THEN the `@Primary` one SHALL be used; WHERE the Spring module's HTTP client support is active (OUT-03), IF none of them is primary, THEN
application startup SHALL fail, even when `spring.main.lazy-initialization` is `true`. `authority.propagation` SHALL be matched with Spring
Boot's relaxed enum binding, as IN-03 matches `inbound.trust`, so `EXCHANGE` means `exchange`, and an empty or blank value SHALL mean `forward`;
IF it matches neither value, such as `bogus`, THEN application startup SHALL fail, even when `spring.main.lazy-initialization` is `true`. WHERE
an `AuthorityPropagator` bean replaces the built-in selection, the `authority.exchange.*` properties SHALL NOT be validated. Verified by IT-4.2,
IT-4.3 and unit tests.

**ID-05** `[M5]` IF the built-in `exchange` propagator is called for a destination that has no service name, THEN it SHALL return empty, so no
`Authorization` header is written (ID-03), SHALL NOT call the token endpoint, and SHALL report one `MISSING_AUDIENCE` violation with the key
`boundrail.authority`, the boundary `INTERNAL_CALL` and the destination. The violation is report-only (DIAG-02), so the request is still sent.
Verified by IT-4.8 and unit tests.

**ID-11** `[M5]` The built-in `exchange` propagator SHALL send each exchange as an HTTP `POST` to `authority.exchange.token-uri` with an
`application/x-www-form-urlencoded` body holding `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`, `subject_token` (the authority's
credential), `subject_token_type=urn:ietf:params:oauth:token-type:access_token` and `audience` (the destination's service name), and SHALL
authenticate with HTTP Basic using `authority.exchange.client-id` and `authority.exchange.client-secret`, each encoded as RFC 6749 §2.3.1 defines. A
valid response SHALL have status 200 and a body that is exactly one JSON object, parsed strictly with a Jackson 3 `ObjectMapper`
(`tools.jackson.databind`), with nothing after it and no key repeated (Jackson's strict duplicate detection), whose `access_token` is a non-empty
string, which SHALL be the returned credential; `token_type` and the response's `Content-Type` SHALL NOT be checked. `expires_in`, when present,
SHALL be a JSON number with a whole value of zero or more, such as `3600`, `3600.0` or `1e3`, giving seconds counted from when the response is
received; a string, `null`, a fraction, a negative number or NaN is not valid. Numbers SHALL be read exactly, not through a `double`, so a fraction
is never rounded to a whole value. A whole value too large for a `long`, or an infinite value, is valid, and the cache lifetime it gives is capped
as ID-04 already caps it, and at the largest instant the implementation can represent. The exchanged token SHALL be cached as ID-04 defines; IF the
response has no `expires_in`, THEN the token SHALL be returned and not cached, and a token whose cache lifetime would be zero or less is not cached
either. The cache SHALL hold at most 10,000 entries; adding an entry to a full cache SHALL first evict the entry added earliest. A cached entry
SHALL NOT be used once its lifetime has passed. Two concurrent calls for the same key with nothing cached MAY both exchange. The cache key SHALL
hold a SHA-256 hash of the credential, never the credential. WHERE the built-in `exchange` propagator is selected and no `AuthorityPropagator` bean
replaces it, IF `authority.exchange.token-uri` is not set, is blank, or is not an absolute URI with the scheme `http` or `https` and a host, or
carries userinfo (client credentials belong in `client-id` and `client-secret`, never in the URI), or `client-id` or `client-secret` is not set or
is blank, or Jackson 3 (`tools.jackson.databind`) is not on the classpath, THEN application startup SHALL fail, even when
`spring.main.lazy-initialization` is `true` (DESIGN D61). Verified by IT-4.3 and unit tests.

**ID-12** `[M5]` IF the token endpoint cannot be reached, does not answer in time, answers with a status other than 200, or answers with a body that
is not valid as ID-11 defines, THEN the `exchange` propagator SHALL throw `TokenExchangeException` and the interceptor SHALL NOT send the request; no
violation is reported. WHERE a deadline is bound when the exchange starts, its request timeout SHALL be the remaining time, and IF it times out, THEN
the propagator SHALL throw `DeadlineExceededException` (stage `TIMEOUT`) instead, with the client's `HttpTimeoutException` as its cause, as DL-07
does, counted once as DL-09 defines. IF the deadline passed DL-06's check but has run out when the exchange is about to start, THEN the propagator
SHALL throw `DeadlineExceededException` (stage `TIMEOUT`), counted once as DL-09 defines, without sending the exchange request, and the interceptor
SHALL NOT send the call. With no deadline bound, the request timeout SHALL be a fixed 10 seconds, with no property. IF the calling thread is
interrupted during the exchange, THEN the propagator SHALL throw `TokenExchangeException` and SHALL itself restore the thread's interrupt status,
because the JDK client clears it. The message of a `TokenExchangeException` SHALL name the audience and, where there is one, the response status, and
SHALL contain no credential, exchanged token, client secret or response body; its cause MAY be the I/O exception that failed the exchange, but an
exception from parsing the response SHALL be neither its cause nor part of its message, because parsers can echo body content (DESIGN D62, D65).
Verified by unit tests.

### Redaction

**ID-06** `[M5]` `AUTHORITY` SHALL NOT be written to `EXTERNAL_CALL`, `MESSAGE`, MDC, logs or violation reports, and SHALL be absent from snapshots
filtered for `DETACHED`. No log entry the library writes, and no message of an exception the library creates, SHALL contain a credential, an
exchanged token or the exchange client secret. `toString()` of the properties record that binds `authority.exchange` SHALL NOT contain the
client secret (DESIGN D63). Verified by IT-4.5, IT-4.6 and unit tests; the `MESSAGE` clause is verified by IT-4.9 in
[007-kafka](../007-kafka/spec.md).

### Documentation

**ID-09** `[M5]` The Javadoc of `BoundExecutors` SHALL state that detached work, including `@Async` methods on executors in the default detached
mode, carries no authority, so its calls to internal services send no `Authorization` header. It SHALL recommend an attached executor when the
caller awaits the result, and a service credential obtained by the task itself otherwise (DESIGN §9, D45). Verified by the Javadoc of
`BoundExecutors`.

### Configuration

Properties use the prefix `boundrail.context.`.

Every startup failure caused by invalid configuration also occurs when `spring.main.lazy-initialization` is `true`: an `authority.propagation` that
is neither `forward` nor `exchange` (ID-04); while the built-in `exchange` propagator is selected, an `authority.exchange.token-uri` that is
missing, blank or not an absolute `http` or `https` URI with a host and no userinfo, or a missing or blank `client-id` or `client-secret`, or
Jackson 3 missing from the classpath (ID-11); and, while HTTP client support is active, several `AuthorityPropagator` beans with none primary
(ID-04).

| Property | Default | Meaning |
|---|---|---|
| `authority.propagation` | `forward` | `forward` or `exchange`, matched with Spring Boot's relaxed enum binding; an empty or blank value means `forward`, and any value that matches neither, such as `bogus`, fails startup, even under lazy initialization; an `AuthorityPropagator` bean overrides it (ID-04) |
| `authority.exchange.token-uri` | unset | RFC 8693 token endpoint, an absolute `http` or `https` URI with a host and no userinfo; required when the built-in `exchange` propagator is selected, and any other value fails startup, even under lazy initialization (ID-11) |
| `authority.exchange.client-id` | unset | Client id for HTTP Basic authentication at the token endpoint; required, not blank, when the built-in `exchange` propagator is selected (ID-11) |
| `authority.exchange.client-secret` | unset | Client secret for HTTP Basic authentication at the token endpoint; required, not blank, when the built-in `exchange` propagator is selected; never in a log entry, exception message or the properties' `toString()` (ID-11, ID-06) |

### Key Entities

- **Authority**: subject plus opaque bearer credential, with an optional expiry; sensitive, redacted in every string form.
- **Initiator**: plain audit identifier (`user:`, `service:`, `system:`), flows everywhere internal.
- **AuthorityPropagator**: decides which credential, if any, goes to a destination.
- **Exchanged token**: an audience-scoped token issued by the token endpoint for a source credential, cached per (credential hash, audience).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: IT-4.1 – IT-4.8 are enabled and green on JDK 21 and JDK 25.
- **SC-002**: alice's token string appears in no log line, MDC entry, violation report or snapshot `toString()` across the suite (IT-4.6).
- **SC-003**: In exchange mode, repeated calls for the same credential and audience hit the token endpoint once until the cached token nears expiry.

## Assumptions

- 003-http-trust, 004-async-diagnostics and 005-deadlines are complete (authentication filter and contributors, detached executors, MDC
  bridge, deadline checks in the interceptor).
- Spring Security stays optional: without it, `ContextAuthenticationFilter` is not registered (IN-04), so no authority or initiator is bound
  from authentication. Binding an authority also needs Spring Security's OAuth2 resource server on the classpath (ID-02).
- The `AUTHORITY` clause that IN-05 defers to M5 is delivered by ID-02.
- The token-exchange client is minimal: client credentials, audience and caching only (DESIGN D61). It needs Jackson 3
  (`tools.jackson.databind`), which `boundrail-context-spring` declares `compileOnly`; Spring Boot's `BasicJsonParser` is too lenient
  (spike T001, DESIGN D65).
- Authority behaviour for Kafka messages belongs to [007-kafka](../007-kafka/spec.md) (IT-4.9).
