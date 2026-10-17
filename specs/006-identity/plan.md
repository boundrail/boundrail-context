# Implementation Plan: Identity

**Branch**: `006-identity` | **Date**: 2026-10-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/006-identity/spec.md`, contract [contracts/identity-api.md](contracts/identity-api.md),
decisions D58 – D63 and the D35 amendment in [design.md](../../docs/design.md), the WIRE-04 sentence of
[003-http-trust](../003-http-trust/spec.md), the merged code of 002 – 005 (`main` at 4eaebfe)

**Status**: Approved by the user on 2026-10-13: plan, tasks, the TDD exception row for the ID-09 Javadoc (T006), and the three guard
scenarios IT-4.4 (T007), IT-4.7 (T008) and IT-4.6 with its two reds (T015). Q1 – Q8 are closed with the recommended answers, now in
spec.md and design.md D64 ([Spec Gaps](#spec-gaps)). The spike T001 ran on 2026-10-13 ([Spike](#spike-t001)); on its outcome the user
chose B2: the built-in exchange propagator requires Jackson 3, `compileOnly` in `-spring`, and fails startup without it. Q9 (duplicate
keys in the token response) is settled: invalid, through Jackson's strict duplicate detection (D65).

## Summary

Authority is only ever established from verified authentication and never leaks, while the initiator provides a complete audit trail.

- `boundrail-context-core`:
  - `Authority.of(subject, credential, expiresAt)` and `expiresAt()` (ID-10, CORE-16);
  - the `AuthorityPropagator` interface (ID-03, ID-04);
  - the `BoundExecutors` Javadoc for ID-09 (TDD exception).
- `boundrail-context-spring`, in the existing auto-configuration:
  - the `boundrail.context.authority.*` properties; the exchange record's `toString()` redacts the client secret (ID-04, ID-06, ID-11);
  - `ContextAuthenticationFilter` binds `AUTHORITY` from an `AbstractOAuth2TokenAuthenticationToken`, whether or not it is already bound,
    and derives `INITIATOR = user:<name>` after trusted headers and contributions; `ContextContributions.put(AUTHORITY, …)` throws
    `IllegalArgumentException` (ID-02, ID-08);
  - `ContextClientInterceptor` asks the effective `AuthorityPropagator` for the `Authorization` value of internal calls, after the
    required-key check, keeps an application-set `Authorization`, applies WIRE-08 but not WIRE-04 to it (ID-03, D60);
  - two package-private propagators: `forward` (default) and `exchange`, a minimal RFC 8693 client on the JDK `HttpClient` with a bounded
    cache keyed by a SHA-256 hash (ID-04, ID-05, ID-11), failing with the public `TokenExchangeException` or, on a deadline,
    `DeadlineExceededException(TIMEOUT)` (ID-12).

Library code is driven by unit and `ApplicationContextRunner`/`MockMvc` tests. Each AuthorityPropagationIT scenario is enabled in the task
whose code makes it pass, so it is that task's failing acceptance test, except IT-4.4, IT-4.6 and IT-4.7, which are guards (green before their
task, each shown red against a named temporary change, [Guard Tests](#guard-tests)).

## Technical Context

**Language/Version**: Java 21 (`--release 21`); nothing in core's `java25` source set

**Primary Dependencies**:

- core: none (BLD-03).
- `-spring`: Spring Boot 4.1.1, Spring Framework 7.0.9, Spring Security 7.1.1. `spring-security-oauth2-resource-server` (which holds
  `AbstractOAuth2TokenAuthenticationToken` and `BearerTokenAuthentication`, and brings `spring-security-oauth2-core`'s `OAuth2Token`) is
  already `compileOnly`. New (T010, decision B2): Jackson 3 `tools.jackson.core:jackson-databind`, `compileOnly` and `testImplementation`,
  version from the Spring Boot BOM (no version in the catalog alias `jackson-databind`). The token exchange uses `java.net.http.HttpClient`
  (JDK) and a strict Jackson 3 `JsonMapper` of its own; with `exchange` selected and no Jackson 3 at runtime, startup fails (T011).
- `integration-tests`: nothing new.

**Storage**: N/A (the exchange cache is in memory, per propagator bean)

**Testing**:

- core unit tests (`AuthorityTest`);
- `-spring` tests with `ApplicationContextRunner`, `WebApplicationContextRunner`, `MockMvc` with `asyncDispatch`, `support/RecordingServer`
  (extended in T010 to answer with a given status and body and to record request bodies), `OutputCaptureExtension`, a fixed `Clock` for
  the cache, and the lazy-initialization setup first used in 003's T006a;
- AuthorityPropagationIT IT-4.1 – IT-4.8 on `integrationTestJdk21` and `integrationTestJdk25`; ContextPropagationIT, TrustBoundaryIT and
  DeadlinePropagationIT re-run in the tasks that change shared topology code.

**Target Platform**: JVM 21+, servlet-based Spring MVC, blocking `RestClient`/`RestTemplate`

**Project Type**: library (Spring Boot auto-configuration)

**Performance Goals**: no reflection per request. Forward mode adds one `Optional` per internal call. Exchange mode adds one SHA-256 of the
credential and one synchronized map lookup per internal call; a miss adds one HTTP round trip

**Constraints**:

- No credential, exchanged token or client secret in a log entry, exception message, MDC entry, violation, snapshot `toString()` or the
  properties' `toString()` (ID-01, ID-06, LOG-02, D63). Cache keys hold a SHA-256 hash, never the credential (ID-04, ID-11).
- The token endpoint is called with the JDK `HttpClient`, never through a `RestClient` or `RestTemplate`, so the library's own interceptor
  never sees the exchange request (no recursion, no context headers sent to the token endpoint).
- RT-09: nothing new is static; the exchange propagator's `HttpClient` and cache belong to one context and are closed with it.
- Principle I: the authority and initiator are bound with `Context.with` in the authentication filter's existing nested scope.
- No work from M6: no Kafka (IT-4.9 stays disabled).

**Scale/Scope**: 1 contract; 12 requirements (CORE-16, ID-02 – ID-06, ID-08 – ID-12); 8 scenarios; a spike, 15 red-green tasks (T002 – T015)
and a close task (T016)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|---|---|---|
| I Scope-only | Pass | `AUTHORITY` and `INITIATOR` join the `ContextBinding` the authentication filter already enters for contributions (ID-02); nothing is set or cleared |
| II Declared propagation | Pass | `AUTHORITY` keeps its built-in rules (no wire name, POL-03); only the propagator decides what an internal call carries (ID-03); external and unknown calls never get it |
| III Secure by default | Pass | Authority only from a verified `AbstractOAuth2TokenAuthenticationToken` (ID-02); never from headers, contributors, detached work, external calls, logs, MDC or violations (ID-06); IT-4.6 and the unit tests pin it |
| IV Declared, never guessed | Pass | The exchange runs within the remaining deadline (ID-12); detached work carries no authority, stated in the `BoundExecutors` Javadoc (ID-09) |
| V Evidence only | Pass | `MISSING_AUDIENCE` and `INVALID_VALUE` for `boundrail.authority` name the key and destination, never the value; a failed exchange is an exception, not a new violation kind (D62) |
| VI Interoperate | Pass | Spring Security decides authentication; the library only reads the verified token. The exchange uses the JDK client, leaving Boot's client configuration alone |
| VII Spec-first, test-first | Pass | Every task is one red-green cycle; the TDD exception row (ID-09 Javadoc) and the three guard scenarios are approved; Q1 – Q8 are closed |
| VIII Simplicity | Pass | Minimal exchange client (D61): one form `POST`, HTTP Basic, one cache map with insertion-order eviction; no retries, no refresh, no property beyond the four in the spec |
| Technical constraints | Pass | Core stays dependency-free; Spring Security and Jackson 3 are `compileOnly`; the OAuth2 type is only touched by a class loaded behind `@ConditionalOnClass`, and Jackson only by `ExchangeAuthorityPropagator`, created after a presence check |

## Spike (T001)

Open questions the plan can answer from the code and the Spring sources, without a spike:

1. **Calling the token endpoint without recursing through the library's interceptor.** The exchange propagator owns a
   `java.net.http.HttpClient`. It is not a Spring client, so no `RestClientCustomizer` or `RestTemplateCustomizer` reaches it: the exchange
   request carries no context headers, is not classified, is not deadline-capped by `DeadlineTimer`, and cannot call the propagator again.
   The JDK client also gives the per-request timeout ID-12 needs (`HttpRequest.Builder.timeout`), which Spring's request factories do not
   (005's T001). It never follows redirects by default, so a 3xx is "a status other than 200" (ID-12). `RestClient.builder()` would also
   avoid the interceptor, but has no per-request timeout.
2. **The RFC 8693 stub in the topology.** `TokenExchangeApp` already exists, runs without Spring Security, is started after edge and is
   reachable as `token-exchange.svc.test`. The exchange edge is started by AuthorityPropagationIT in `@BeforeAll` with
   `Topology.Running.startEdge(...)`, after the shared topology is up, so its `token-uri` is `topology.uri(TOKEN_EXCHANGE) + "/token"`.
3. **Spring Security types at compile time.** `AbstractOAuth2TokenAuthenticationToken<T extends OAuth2Token>` (`getToken()`, `getName()`) is
   in `spring-security-oauth2-resource-server` 7.1.1, already `compileOnly` in `-spring`; `OAuth2Token.getTokenValue()` and the default
   `getExpiresAt()` are in `spring-security-oauth2-core`, its dependency. `AuthenticationConfiguration` is only conditional on
   `SecurityContextHolder`, so the filter must not reference the OAuth2 type directly: a package-private `AuthorityExtraction` bean is
   defined in a nested configuration conditional on `AbstractOAuth2TokenAuthenticationToken`, and the filter takes it through an
   `ObjectProvider` ([Spring wiring](#spring-wiring)).

T001 covered the response parsing and the JDK client's failure modes. It ran on 2026-10-13; no code from it is kept.

**JSON parsers.**

- `JsonParserFactory.getJsonParser()` picks Jackson 3, then Gson, then `BasicJsonParser`. `-spring`'s compile, runtime and test
  classpaths have neither Jackson nor Gson, so they would get `BasicJsonParser`; the `integration-tests` classpath has Jackson 3.1.5
  (`spring-boot-starter-webmvc` → `spring-boot-starter-jackson`).
- `BasicJsonParser` accepts malformed objects and can return a wrong `access_token`: trailing garbage gives the token `t"} trailing`, JSON
  `null` gives the string `"null"`, truncated bodies parse, and escapes are mangled (`\u0041` → `u0041`).
- Gson is lenient about syntax only (unquoted names, bare words as strings), returns every number as `Double`, and rejects duplicate keys.
- Jackson 3 is strict (`FAIL_ON_TRAILING_TOKENS` on), but a whole body `null` gives a `null` map.
- Every parser turns `1e400` into `Infinity` (as a `double`); `BasicJsonParser` gives `NaN`; `BigDecimal` throws on non-finite doubles.
- Parse exceptions (and their causes) can echo body content: Basic `Unable to parse '"SECRET"'`, Jackson `Unrecognized token 'garbage'`.

**JDK `HttpClient`.**

| Situation | Outcome |
|---|---|
| Connection refused, host unresolvable | `ConnectException`, `null` message |
| Unroutable address, with a request timeout | `HttpConnectTimeoutException`, a subclass of `HttpTimeoutException`: the request timeout covers the connect |
| Server closes without answering | `IOException` `header parser received no bytes` |
| Slow response | `HttpTimeoutException` `request timed out` |
| Non-2xx | No exception; `statusCode()` |
| 302 | Not followed |
| `timeout(Duration.ZERO)` or negative | `IllegalArgumentException` from the builder: the "run out" check must come before setting the timeout |
| `HttpRequest.newBuilder` on `http:foo`, `http:///x` | `IllegalArgumentException` `unsupported URI …` (`getHost()` is `null`); the message echoes the whole URI, userinfo included |
| Interrupt before or during `send` | `InterruptedException`, and the interrupt flag is cleared: the library must restore it |

No client exception message contained the form's secrets, the Basic credentials or URI userinfo, except the `unsupported URI` message above.

**Decision B2 (user, 2026-10-13).** The built-in exchange propagator requires Jackson 3 (`tools.jackson.databind`), `compileOnly` and
`testImplementation` in `-spring` (BOM-managed). It parses with its own `JsonMapper`, strict, with `USE_BIG_DECIMAL_FOR_FLOATS` and
`USE_BIG_INTEGER_FOR_INTS` so numbers are exact; never the application's mapper bean, whose customisation could make it lenient. With
`exchange` selected (and no replacing bean) and Jackson 3 absent, startup fails, also with lazy initialization. `NaN` is not valid; an
infinite or beyond-`long` `expires_in` is too large, so valid and capped. The parse exception is never a cause and never in a message. The
propagator catches `HttpTimeoutException` before the general `IOException`. Q3 and Q5 are confirmed by the spike; Q9 is new.

## Research Notes

### Core

- **Authority expiry (ID-10, CORE-16).** A third field `Instant expiresAt` (nullable inside the class); `of(subject, credential, expiresAt)`
  checks all three with `Objects.requireNonNull`; `expiresAt()` returns `Optional.ofNullable(expiresAt)`. `toString()`, `equals` and
  `hashCode` unchanged (Q1).
- **`AuthorityPropagator`.** The contract's one-method interface, no default implementation in core. Its Javadoc states that the result is
  sent as `Bearer <credential>` to internal destinations only.
- **ID-09.** One paragraph in the `BoundExecutors` class Javadoc, next to "Expired deadlines": detached work, including `@Async` methods on
  executors in the default detached mode, carries no authority, so its calls to internal services send no `Authorization` header; use an
  attached executor when the caller awaits the result, and otherwise a service credential the task obtains itself (DESIGN §9, D45).

### Spring wiring

- **Properties.** `BoundrailContextProperties` gains `@DefaultValue Authority authority`, the record `Authority(@DefaultValue("forward")
  Propagation propagation, @DefaultValue Exchange exchange)` with `enum Propagation { FORWARD, EXCHANGE }`, bound with relaxed enum binding
  as `inbound.trust` is (blank → default, `bogus` fails binding, which the `@Lazy(false)` reporter forces at startup). `Exchange(String
  tokenUri, String clientId, String clientSecret)` validates nothing itself, because ID-04 forbids validating it when a bean replaces the
  built-in selection; its `toString()` prints `clientSecret=<redacted>` when set (D63), so the enclosing records' `toString()` is safe too.
  The nested record's simple name `Authority` is fixed by the contract; inside `BoundrailContextProperties` the core type is written fully
  qualified where needed.
- **Authority binding (ID-02, ID-10).** A package-private functional interface `AuthorityExtraction { Optional<Authority>
  from(Authentication) }`. Its one implementation, in a nested configuration of `AuthenticationConfiguration` conditional on
  `AbstractOAuth2TokenAuthenticationToken`, returns `Authority.of(getName(), token.getTokenValue(), token.getExpiresAt())`, or the two-argument
  form when `getExpiresAt()` is `null`, for an `AbstractOAuth2TokenAuthenticationToken`, and empty otherwise. Without the resource server
  the filter receives none and binds no authority. In `doFilterInternal`, for an authenticated request:
  1. contributors run as today; `Contributions.put` throws `IllegalArgumentException` for `AUTHORITY` after its two `null` checks and before
     the type cast (ID-02, CORE-13);
  2. `AUTHORITY` from the extraction joins the binding unconditionally (the IN-05 exception);
  3. `INITIATOR`: if neither bound (trusted header) nor contributed, `"user:" + getName()`; `Initiator.of` throwing
     `IllegalArgumentException` (empty is impossible, so only length) leaves it unbound and reports one `INVALID_VALUE` for
     `boundrail.initiator`, `INTERNAL_CALL`, no destination (DIAG-08), through the same path as `Contributions.invalid`;
  4. if the binding holds anything, the chain runs inside it and `SNAPSHOT_ATTRIBUTE` is replaced, as today; so every authenticated request
     now enters the nested scope (it always derives an initiator unless one is bound or the name is too long).
- **Interceptor (ID-03).** `ContextClientInterceptor.intercept` keeps its order (DL-06, classify, POL-08, OUT-04, WIRE-07) and, for
  `INTERNAL_CALL` only, adds the authority step after `writeHeaders` and before the deadline is read, so the time an exchange takes is
  subtracted from the `Boundrail-Deadline-Ms` value and the DL-07 cap:
  1. `request.getHeaders().getOrEmpty("Authorization")` not empty → leave it, call nothing (OUT-04's "already set"; `HttpHeaders` compares
     names ignoring case, WIRE-06);
  2. `AUTHORITY` absent from the snapshot filtered for `INTERNAL_CALL` → nothing;
  3. `propagator.outboundCredential(authority, destination)`; empty → nothing, nothing reported; present → `"Bearer " + credential`, checked
     with the existing `isFieldValue` (WIRE-08) but not with `maxValueLength` (D60); invalid → one `INVALID_VALUE` for `boundrail.authority`
     with `INTERNAL_CALL` and the destination, header not written; valid → `set` (which also replaces a name present with no values).
  `TokenExchangeException` and `DeadlineExceededException` thrown by the propagator propagate unchanged, so the request is not sent.
  The interceptor receives the `AuthorityPropagator` through its constructor, from the two customizers.
- **Propagator selection (ID-04).** A top-level `@Bean @Lazy(false) @ConditionalOnMissingBean(AuthorityPropagator.class)` method
  `boundrailAuthorityPropagator` returns the forward or exchange propagator by `authority.propagation`; it is top-level, like
  `HostPatternClassifier` (DEST-04), so the exchange settings are validated whenever the built-in `exchange` is selected (ID-11). The two
  `@Lazy(false)` customizers in `ClientConfiguration` inject `AuthorityPropagator` directly, so several beans with none primary fail startup
  through `NoUniqueBeanDefinitionException`, also under lazy initialization, and only where client support is active (OUT-03's pattern).
- **Forward propagator.** A package-private class whose `outboundCredential` checks both arguments (CORE-16) and returns
  `Optional.of(authority.credential())`.
- **Exchange propagator (ID-04, ID-05, ID-11, ID-12).** Package-private `ExchangeAuthorityPropagator implements AuthorityPropagator,
  AutoCloseable`, created with the token URI, client id and secret, the `ViolationReporter`, the `DeadlineCounter`, a `Clock` and the
  no-deadline timeout (10s in production; the package-private constructor lets tests pass less). `close()` closes the `HttpClient`
  (JDK 21), called by Spring's inferred destroy method when the context closes (RT-09).
  - Startup validation in the bean method: Jackson 3 present (`ClassUtils.isPresent("tools.jackson.databind.json.JsonMapper",
    beanClassLoader)`, so `FilteredClassLoader` proves it; checked before `ExchangeAuthorityPropagator` is loaded, the only class naming a
    Jackson type); `token-uri` set, not blank, `URI.create` succeeds, absolute, scheme `http` or `https` ignoring case, and `getHost()` not
    `null` (Q5; checked directly, not through `HttpRequest.newBuilder`, whose message echoes the URI with its userinfo); `client-id` and
    `client-secret` set and not blank. `IllegalArgumentException` naming the property (or Jackson 3), never the secret, and with no cause.
  - `outboundCredential`: `requireNonNull` both; no service name → report `MISSING_AUDIENCE` (`boundrail.authority`, `INTERNAL_CALL`,
    destination) and return empty; key `(sha256Hex(credential), audience)`; a live cache entry → its token; otherwise exchange.
  - Request: `POST`, `Content-Type: application/x-www-form-urlencoded`, body of the four fields each `URLEncoder.encode(…, UTF_8)`,
    `Authorization: Basic base64(urlencode(id) + ":" + urlencode(secret))` (RFC 6749 §2.3.1), `Accept: application/json`; timeout: the
    remaining deadline if one is bound, else the fixed timeout. A remaining time of zero or less is checked before the timeout is set (the
    builder throws `IllegalArgumentException` on it) and is the Q2 `TIMEOUT`, with no request sent.
  - Response: status 200 and a body that the strict `JsonMapper` parses to a JSON object (a whole-body `null` or any non-object is invalid)
    whose `access_token` is a non-empty string; `expires_in` absent, or a `BigInteger`/`BigDecimal` that is a whole number ≥ 0 (Q3; a
    boolean, string, `null` or fraction is invalid; `NaN` is not JSON, so the parse fails). Anything else →
    `TokenExchangeException("Token exchange for audience <a> failed with status <s>")` or `"… failed: <reason>"`, where the reason is fixed
    text ("no valid response", "endpoint unreachable", "timed out"), never the body. A parse failure gives "no valid response" with no
    cause (its message can echo the body, B2).
  - Exceptions from `send`, caught in this order: `HttpTimeoutException` (including `HttpConnectTimeoutException`) with a deadline bound →
    `counter.increment(TIMEOUT)` and `DeadlineExceededException(TIMEOUT, e)`, without one → `TokenExchangeException("timed out", e)`; any
    other `IOException` → `TokenExchangeException("endpoint unreachable", e)`; `InterruptedException` → interrupt status restored,
    `TokenExchangeException` with it as the cause (Q6).
  - Cache: a `LinkedHashMap` in insertion order under a lock, at most 10,000 entries; `removeEldestEntry` evicts the earliest added; an
    expired entry is removed on lookup, so its replacement counts as newly added. Entry end = `min(received + expires_in − 30s,
    authority.expiresAt())`, with an `expires_in` beyond `long` seconds or past `Instant.MAX` capped at the source expiry or `Instant.MAX`
    (B2); not cached if `expires_in` is absent or the end is not after `received` (ID-11). Two concurrent misses may both
    exchange. A package-private `cacheKeys()` exposes the keys to tests, and `httpClient()` the client (RT-09).
- **`TokenExchangeException`.** Public final class in `io.github.boundrail.context.spring`, `extends RuntimeException`, package-private
  constructors `(String audience, int status)`, `(String audience, String reason)` and `(String audience, String reason, Throwable cause)`.
- **RT-09.** Nothing new is static; the cache and client are per bean.

### Integration tests

- **Recordings.** `ObservedContext.ping` adds `ctx.authority-subject` (when `AUTHORITY` is bound) and `ctx.initiator` (when `INITIATOR` is
  bound), read at handler entry, next to the existing entries, so every service records them (T006). IT-4.6 also needs, in edge's
  handlers and in its detached task, `ctx.mdc` (the MDC map's `toString()`) and `ctx.snapshot` (`Context.capture().toString()`) (T015).
- **Token-exchange stub (T012).** `TokenExchangeApp.token` takes the form (`@RequestParam MultiValueMap<String, String>`), counts the call,
  records its headers plus `form.grant_type`, `form.subject_token`, `form.subject_token_type` and `form.audience` (the test compares
  `form.subject_token` with the token it sent) and the issued token under `issued`, and answers `200` with
  `{"access_token":"exchanged-<audience>-<uuid>","issued_token_type":"urn:ietf:params:oauth:token-type:access_token",
  "token_type":"Bearer","expires_in":3600}`, a `@ResponseBody` map serialised by Boot's Jackson.
- **Exchange edge (T012).** AuthorityPropagationIT starts it in `@BeforeAll` with `startEdge("boundrail.context.authority.propagation=exchange",
  "boundrail.context.authority.exchange.token-uri=" + topology.uri(TOKEN_EXCHANGE).resolve("/token"),
  "boundrail.context.authority.exchange.client-id=edge", "boundrail.context.authority.exchange.client-secret=edge-secret")` and closes it in
  `@AfterAll`. Its violation deltas are read from its own `MeterRegistry`, as DeadlinePropagationIT does.
- **Unique tokens (T012).** `TestJwts.claims()` adds a random `jti`, so two `alice()` calls in the same second never give the same token and
  IT-4.3's "exactly one request" cannot be satisfied by a cache entry from another scenario. Subject and `tenant` are unchanged.
- **Edge endpoints.** `/authority/detached` (T006): records, calls partner through `external()`, runs a task on `taskExecutor` that records
  `detached-authority` (bound or not) and `detached-initiator`. `/public/ping` (T007) behind an edge `SecurityFilterChain`: `/public/**`
  `permitAll`, everything else authenticated, `oauth2ResourceServer(jwt)`, as Boot's default chain does for the rest.
  `/authority/call-orders/service-token` (T008): sets `Authorization: Bearer service-token` on an `internal("orders")` call.
  `/authority/call-orders/twice` (T012). `/authority/call-orders/undeclared` (T015): calls `orders.svc.test` through a client with no
  declaration and `TestInboundTrustVerifier.peer()`.
- **Shared JVM.** Observations, the stub's counter and the meters are shared: each scenario uses a marker and asserts deltas.

### Verify commands

Unit: `./gradlew :<project>:test --tests '<Class>'`. Scenarios (Gradle applies `--tests` to the task just before it):

```bash
./gradlew --continue :integration-tests:integrationTestJdk21 --tests '*AuthorityPropagationIT' \
          :integration-tests:integrationTestJdk25 --tests '*AuthorityPropagationIT'
```

Tasks that change shared topology code (T006, T007, T008, T012, T015) also run `*ContextPropagationIT`, `*TrustBoundaryIT` and
`*DeadlinePropagationIT` the same way. Every task ends with `./gradlew check`.

## Spec Gaps

Q1 – Q8 are closed by the user's decisions on 2026-10-13, each with the recommended answer, now in [spec.md](spec.md) and design.md;
their tests are in the tasks named. Q9 came from T001 and is closed by the user's decision (invalid; D65).

| Gap | Status | Question | Decision | Tasks |
|---|---|---|---|---|
| Q1 | Closed | What does `Authority.toString()` show for an authority with an expiry? The contract fixes `Authority[subject=alice, credential=<redacted>]` | Unchanged for both factories: the expiry is not printed (it is not secret, but nothing needs it, and one format keeps ID-01's test exact) | T002 |
| Q2 | Closed | The deadline is bound and still unexpired at DL-06's check, but has expired (remaining zero) when the exchange starts; the JDK client rejects a zero timeout | Treat it as the exchange timing out: `DeadlineExceededException(TIMEOUT)`, counted once, no exchange request sent, the call not sent | T014 |
| Q3 | Closed | Which JSON values are "a non-negative integer number of seconds" for `expires_in`? Gson parses every number as a `Double` | A JSON number whose value is a whole number ≥ 0 (`3600`, `3600.0`, `1e3`); a string, `null`, a boolean, a fraction or a negative value is not valid; a value beyond `long` seconds (`1e400`, infinite) is valid and the cache end is capped (by the source expiry or `Instant.MAX`); `NaN` is not valid. T001 confirmed; with B2 Jackson gives exact `BigInteger`/`BigDecimal` | T012, T013 |
| Q4 | Closed | Is the response's `Content-Type` checked? | No; only status 200 and a body that parses as a JSON object (ID-11 does not name a media type, and some servers send `application/json;charset=UTF-8` or omit it) | T010, T013 |
| Q5 | Closed | Must `token-uri` have a host? `http:foo` and `http:///x` are absolute `http` URIs, which ID-11 accepts, but the JDK client rejects them at the first exchange | Yes: a URI without a host fails startup like a relative one. T001 confirmed the JDK rejects both (`getHost()` is `null`); the check is `getHost() == null`, because the builder's message echoes the URI with its userinfo | T011 |
| Q6 | Closed | The calling thread is interrupted while waiting for the token endpoint (not by DL-07, whose timer is not yet armed) | `TokenExchangeException` with the `InterruptedException` as the cause and the interrupt status restored; the call is not sent | T013 |
| Q7 | Closed | Is the `TIMEOUT` exception's cause the JDK client's `HttpTimeoutException`, as DL-07 gives the client's exception as the cause? | Yes | T014 |
| Q8 | Closed | `ContextContributions.put(AUTHORITY, value)` with a value of the wrong type through a raw key: `ClassCastException` (CORE-13's cast) or `IllegalArgumentException` (ID-02)? | `IllegalArgumentException`: the `AUTHORITY` check comes right after the `null` checks, before the cast, as ID-02 says "after its `null` checks" | T005 |
| Q9 | Closed: not valid, through `STRICT_DUPLICATE_DETECTION` (D65) | A token response with a duplicate key (`{"access_token":"a","access_token":"b"}`): valid, and which value? Jackson's default keeps the last value silently; Gson rejects it; RFC 8259 leaves it undefined | Recommended: not valid ("no valid response"), by enabling `StreamReadFeature.STRICT_DUPLICATE_DETECTION` on the propagator's `JsonMapper`, so no parser-dependent token is ever sent | T013 (its duplicate-key row only) |

## Guard Tests

These tests already pass before the task that introduces them. As in 002 – 005, each red is shown against a named temporary change, which
is reverted before the commit. They are not TDD exceptions; the user accepted all three on 2026-10-13.

A test that is green before its task is accepted as a guard when its red is shown against a named temporary change to the finished code, which
is then reverted; the leader reports it in the task report, the planner adds it to this table, and the reviewer checks it.

A negative assertion that sits in the same test as a positive assertion that is red before its task is not a guard, and is not listed (for
example, "external and unknown calls get no `Authorization`" sits with "an internal call gets `Bearer <credential>`" in T008, and "the
authority is not in the MDC or in the snapshot filtered for `DETACHED`" with "the authority is bound" in T004).

| Task | Scenario or test | Why green first | Red shown against (temporary, reverted) |
|---|---|---|---|
| T007 | IT-4.4 No verified credential, no authority | An anonymous request never had an authority or initiator, and untrusted headers were reported since M2 | ID-08's derivation using `authentication != null && authentication.isAuthenticated()` instead of the trust resolver, so the anonymous token gives `user:anonymousUser` |
| T008 | IT-4.7 Application-set Authorization is respected | Before ID-03 nothing wrote `Authorization`, so the application's header always passed | The interceptor's ID-03 step without the "already set" check, so it replaces the header with alice's token |
| T011 | `ID-04 with an AuthorityPropagator bean, or with forward, the exchange properties are not validated` | Before T011 no property was validated, so both contexts started | (a) `forward` also validating `token-uri`; (b) a separate `@Lazy(false)` bean validating whenever `exchange` is selected, ignoring the replacing bean: the token-uri failure |
| T015 | IT-4.6 No credential in any output | A negative property of all M5 code; the earlier tasks pin each part in unit tests | (a) `Authority.toString()` printing the credential: the recorded snapshot `toString()` fails; (b) `MdcScopeListener` not skipping `AUTHORITY`: the recorded MDC fails |

## TDD Exceptions

Approved by the user on 2026-10-13.

| Task | What | Verified by |
|---|---|---|
| T006 | The Javadoc statement of `BoundExecutors` required by ID-09: detached work, including `@Async` methods on executors in the default detached mode, carries no authority, so its calls to internal services send no `Authorization` header; an attached executor is recommended when the caller awaits the result, and a service credential obtained by the task itself otherwise. Documentation, with no behaviour a test can fail on; the behaviour itself is tested by IT-4.5 | Review of T006, and inspection in T016 against ID-09's wording |

T001 is a spike, not an exception: no code from it was kept. Test support changes (`RecordingServer`, topology code) are part of the red
step of the task that needs them, and so is T010's Jackson 3 dependency, which T010's tests need to compile.

## Project Structure

### Documentation (this feature)

```text
specs/006-identity/
├── spec.md
├── plan.md                # this file
├── tasks.md
└── contracts/identity-api.md
```

### Source Code (repository root)

```text
boundrail-context-core/src/main/java/io/github/boundrail/context/
├── Authority.java (expiry)                                          # T002
├── AuthorityPropagator.java                                        # T008
└── BoundExecutors.java (Javadoc, ID-09)                             # T006
boundrail-context-core/src/test/java/io/github/boundrail/context/
└── AuthorityTest.java                                              # T002
boundrail-context-spring/src/main/java/io/github/boundrail/context/spring/
├── BoundrailContextProperties.java (Authority, Exchange)            # T003
├── BoundrailContextAutoConfiguration.java                          # T004, T006, T008, T010, T011
├── ContextAuthenticationFilter.java (ID-02, ID-08)                 # T004 – T007
├── AuthorityExtraction.java (package-private)                      # T004
├── ContextContributions.java (Javadoc of put)                      # T005
├── ContextClientInterceptor.java (ID-03)                           # T008, T009
├── ForwardAuthorityPropagator.java (package-private)               # T008
├── ExchangeAuthorityPropagator.java (package-private, Jackson 3)   # T010 – T015
└── TokenExchangeException.java                                     # T013
boundrail-context-spring/src/test/java/io/github/boundrail/context/spring/
├── AuthorityPropertiesTest.java  AuthorityBindingTest.java  InitiatorDerivationTest.java
├── AuthorityPropagationTest.java
├── TokenExchangeTest.java  TokenExchangeConfigurationTest.java  TokenExchangeCacheTest.java  TokenExchangeFailureTest.java
├── ContextAuthenticationFilterTest.java  ContextClientInterceptorTest.java (one test replaced)
└── support/RecordingServer.java (status, body, request bodies)      # T010
boundrail-context-spring/build.gradle.kts (Jackson 3 compileOnly, testImplementation)  # T010
gradle/libs.versions.toml (jackson-databind, no version)                              # T010
integration-tests/src/test/java/io/github/boundrail/context/it/
├── AuthorityPropagationIT.java (IT-4.1 – IT-4.8)
└── topology/ObservedContext.java  EdgeApp.java  TokenExchangeApp.java  TestJwts.java
```

**Structure Decision**: The contract's `Authority` methods, `AuthorityPropagator`, `TokenExchangeException` and the properties record are
the only public changes. Everything else new in `-spring` is package-private, as in 003 – 005.

## Requirement Coverage

| Requirement or scenario | Tasks |
|---|---|
| CORE-16 | T002 (`Authority.of`), T005 (`put` order), T008 (forward), T010 (exchange) |
| ID-02 | T004 (binding, expiry, scope, IN-05 exception, non-OAuth2, no resource server), T005 (`put(AUTHORITY)`) |
| ID-03 | T008 (internal only, application-set kept, empty, ordering, D60), T009 (WIRE-08) |
| ID-04 | T003 (`propagation` binding), T008 (forward default, bean replaces, `@Primary`, none primary, lazy), T012 (cache) |
| ID-05 | T015 |
| ID-06 | T003 (properties `toString`), T004 (MDC, `DETACHED`), T008 (external, unknown), T011 (startup messages), T013 (exception messages, logs), T015 (IT-4.6, guard) |
| ID-08 | T006 (derivation, precedence, non-OAuth2), T007 (invalid name, unauthenticated) |
| ID-09 | T006 (Javadoc: TDD exception) |
| ID-10 | T002, T004 (bound from `getExpiresAt()`) |
| ID-11 | T010 (request, response, whole-body `null`), T011 (startup validation, Jackson 3 required), T012 (cache, capped lifetime), T013 (validity) |
| ID-12 | T013 (failures, message), T014 (timeouts) |
| IT-4.1 | T006 |
| IT-4.2 | T008 |
| IT-4.3 | T012 |
| IT-4.4 | T007 (guard) |
| IT-4.5 | T006 |
| IT-4.6 | T015 (guard) |
| IT-4.7 | T008 (guard) |
| IT-4.8 | T015 |
| SC-001 – SC-003 | T016 |

## Risks

- **Existing tests that M5 contradicts.**
  - `OUT-04 an internal call writes no Authorization` (`ContextClientInterceptorTest`) binds `AUTHORITY` and asserts no `Authorization`:
    ID-03 contradicts it. T008 replaces it by the ID-03 tests (its `Tenant-Id` assertion is already in `OUT-04 an internal call carries the
    bound keys that propagate to INTERNAL_CALL`).
  - IN-05's deferred clause ("the exception for `AUTHORITY` … is delivered with ID-02") has no test encoding the old behaviour; T004 adds the
    ID-02 test. No `ContextAuthenticationFilterTest` or `AsyncDispatchTest` test asserts that an authenticated request without
    contributions runs in the current scope, but after T006 every authenticated request enters the nested scope; T006 re-runs both classes.
  - No 003 test asserts that a forwarded JWT is length-checked; D60 is new behaviour pinned in T008.
- **Shared topology.** After T006 every edge request carries `INITIATOR`, and after T008 every internal call from edge carries alice's token.
  IT-1 – IT-3 assert with `containsEntry`/`doesNotContainKeys` on named entries, and IT-1.7 asserts the scheduled job's exact context,
  which ID-08 does not touch; the IT+ runs in T006 and T008 confirm it. T007's edge `SecurityFilterChain` replaces Boot's default chain:
  TopologySmokeIT's 401 for a foreign token and IT-3.3's 504 through the `ERROR` dispatch (005's T001 item 5) must still hold; T007 runs
  every suite.
- **Jackson 3 optional at runtime (B2).** An application with `forward` and no Jackson must still start: only `ExchangeAuthorityPropagator`
  names a Jackson type, the auto-configuration names it only inside the bean method after the presence check, and T011's
  `FilteredClassLoader` test covers `forward`, a replacing bean and `exchange`. T016 checks by inspection that no other class imports
  `tools.jackson`.
- **Parse errors echo the body (T001).** Jackson's and Basic's messages quote body content; the parse exception is dropped, never a cause,
  never logged (T013's ID-06 test uses a body whose parse error would echo a secret).
- **Credential exposure in new code.** Exception messages and startup failures are built from fixed text plus the audience, status or
  property name only; T011 and T013 assert the absence of the credential, exchanged token, client secret and body.
- **Optional resource server.** The filter must load without `spring-security-oauth2-resource-server`; `FilteredClassLoader` only proves the
  condition, so `AuthorityExtraction`'s implementation is the only class naming the OAuth2 type, and review checks no other class does.

## Complexity Tracking

No constitution violations to justify.
