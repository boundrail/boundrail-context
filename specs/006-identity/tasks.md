---
description: "Task list for 006-identity"
---

# Tasks: Identity

**Input**: Design documents from `/specs/006-identity/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [contracts/identity-api.md](contracts/identity-api.md); 005-deadlines merged
(4eaebfe), `main` at 4eaebfe

**Tests**: Required (constitution principle VII). Each task is one red-green cycle: its failing test, with the behaviour it pins down, and the
minimum code that makes it pass, committed and reviewed together. Every task leaves `./gradlew check` green. Every test's display name starts
with the requirement or scenario ID it covers.

- Approved by the user on 2026-10-13: these tasks, the plan, the TDD exception for the ID-09 Javadoc (T006), and the guard scenarios
  IT-4.4 (T007), IT-4.7 (T008) and IT-4.6 with its two reds (T015) ([plan.md](plan.md#tdd-exceptions), [plan.md](plan.md#guard-tests)).
- Q1 – Q8 are closed with the recommended answers, which the spec-creator writes into spec.md and design.md
  ([plan.md](plan.md#spec-gaps)). Q9 (duplicate keys in the token response) came from T001 and is closed: a repeated key makes
  the response invalid (D65).
- T001, the spike, ran on 2026-10-13; on its outcome the user chose B2: the built-in exchange propagator requires Jackson 3
  (`compileOnly` in `-spring`) and fails startup without it ([plan.md](plan.md#spike-t001)).
- A test that is green before its task is accepted as a guard when its red is shown against a named temporary change to the finished code,
  which is then reverted; the leader reports it in the task report, the planner adds it to plan.md's Guard Tests table, and the reviewer
  checks it. A negative assertion in the same test as a positive assertion that is red before the task is not a guard.
- Review follow-ups get a letter suffix (T004a, …).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: The user story the task belongs to (US1: authority from verified authentication, forwarded; US2: token exchange; US3:
  authority never leaks)

Path abbreviations:

| Short | Path |
|---|---|
| `core/` | `boundrail-context-core/src/main/java/io/github/boundrail/context/` |
| `coretest/` | `boundrail-context-core/src/test/java/io/github/boundrail/context/` |
| `spring/` | `boundrail-context-spring/src/main/java/io/github/boundrail/context/spring/` |
| `springtest/` | `boundrail-context-spring/src/test/java/io/github/boundrail/context/spring/` |
| `it/` | `integration-tests/src/test/java/io/github/boundrail/context/it/` |
| `topology/` | `integration-tests/src/test/java/io/github/boundrail/context/it/topology/` |

Verify commands:

- **Unit**: `./gradlew :<project>:test --tests '<TestClass>'` for red and green, then `./gradlew check` before commit.
- **IT**: the scenario run, then `./gradlew check` before commit:

  ```bash
  ./gradlew --continue :integration-tests:integrationTestJdk21 --tests '*AuthorityPropagationIT' \
            :integration-tests:integrationTestJdk25 --tests '*AuthorityPropagationIT'
  ```

- **IT+**: the IT run plus `--tests '*ContextPropagationIT'`, `--tests '*TrustBoundaryIT'` and `--tests '*DeadlinePropagationIT'` on both
  tasks, for tasks that change shared topology code.

Enabling a scenario means: remove `@Disabled`; keep its `@DisplayName` and the comment with the spec text; write the body. Scenarios use a
marker and assert deltas, never totals, because the topology is shared. "Alice's token" is a fresh `topology.jwts().alice()` per scenario.

"Lazy initialization" in a test means the setup first used in 003's T006a (`spring.main.lazy-initialization=true` and a
`LazyInitializationBeanFactoryPostProcessor`).

## Phase 0: Spike

- [x] T001 Spike: token response parsing per JSON parser, and the JDK `HttpClient`'s failure modes.
  - On a scratch classpath, run the ID-11 validity table of [plan.md](plan.md#spike-t001) through Jackson 3's, Gson's and Boot's basic
    parser via `JsonParserFactory`; record which parser `-spring`'s test classpath and the `integration-tests` runtime select; record the
    JDK client's exceptions for a refused connection, a request timeout, a zero `timeout`, and `HttpRequest.newBuilder` on `http:foo` and
    `http:///x`.
  - Outcome into plan.md (Spike), with a recommendation for Q3 and Q5 and, if `BasicJsonParser` accepts non-object bodies, the choice
    between accepting it and a `compileOnly` Jackson 3 parser. No code kept, nothing committed.
  - Done 2026-10-13: outcome in [plan.md](plan.md#spike-t001); the user chose B2 (Jackson 3 required).

---

## Phase 1: Setup

None. 001 – 005 built the projects, the integration test tasks, the topology (including `TokenExchangeApp`), the auto-configuration and the
disabled scenarios. The one build change, Jackson 3 in `-spring`, is part of T010's red step (its tests need it to compile).

---

## Phase 2: Foundational

- [x] T002 [P] `Authority` expiry.
  - Red, in `coretest/AuthorityTest.java`:
    - `ID-10 Authority.of with an expiry returns it from expiresAt, and of(subject, credential) has none`: the given `Instant`; empty for the
      two-argument factory; `subject()` and `credential()` as given;
    - `ID-10 an authority with an expiry keeps ID-01: toString without the credential, identity-based equals and hashCode`: `toString()` is
      exactly `Authority[subject=alice, credential=<redacted>]` (Q1); two equal-looking authorities are not equal; `hashCode` is the identity
      hash;
    - `CORE-16 Authority.of rejects a null subject, credential or expiry with NullPointerException`: each of the three arguments `null`,
      and the message never contains the credential.
  - Green: the third field, the factory and the accessor in `core/Authority.java` ([plan.md](plan.md#core)).
  - Completes: ID-10; CORE-16 (`Authority.of`).
  - Verify: Unit `AuthorityTest` in `:boundrail-context-core`.
- [x] T003 [P] The `authority.*` properties: binding, relaxed enum, and a `toString()` without the client secret.
  - Red, in `springtest/AuthorityPropertiesTest.java` (`ApplicationContextRunner`, reading `BoundrailContextProperties.authority()`):
    - `ID-04 authority.propagation defaults to forward and the exchange properties to unset`;
    - `ID-04 authority.propagation is matched with relaxed enum binding, and an empty or blank value means forward` (parametrized):
      `exchange`, `EXCHANGE`, ` Exchange ` give `EXCHANGE`; ``, `   ` give `FORWARD`;
    - `ID-04 an authority.propagation that is neither forward nor exchange fails startup, also with lazy initialization`: `bogus`, with and
      without lazy initialization;
    - `ID-11 the exchange properties are bound as given and not validated by binding`: `token-uri`, `client-id`, `client-secret` bound
      verbatim; `token-uri=relative` with `propagation=forward` starts;
    - `ID-06 toString of the exchange properties and of BoundrailContextProperties does not contain the client secret`: with
      `client-secret=s3cr3t`, neither `toString()` contains `s3cr3t`, and the exchange record's shows `clientSecret=<redacted>`; an unset
      secret shows `clientSecret=null`.
  - Green: the `Authority` and `Exchange` records and `Propagation` enum in `spring/BoundrailContextProperties.java`, with `Exchange.toString()`
    redacting the secret ([plan.md](plan.md#spring-wiring)).
  - Completes: ID-04 (property binding); ID-06 (properties `toString`); the Configuration section's `authority.propagation` failure.
  - Verify: Unit `AuthorityPropertiesTest` in `:boundrail-context-spring`.

**Checkpoint**: the contract's `Authority` change and the properties exist; nothing reads them yet.

---

## Phase 3: User Story 1 - Authority from verified authentication, forwarded to internal calls (Priority: P1) 🎯 MVP

**Goal**: The edge binds authority and initiator from the verified authentication; internal calls carry the caller's credential unless the
application set its own.

**Independent Test**: IT-4.1, IT-4.2, IT-4.4 and IT-4.7 in the IT command (IT-4.5 of US3 is enabled here too, in T006).

- [x] T004 [US1] Bind `AUTHORITY` from an `AbstractOAuth2TokenAuthenticationToken`.
  - Red, in `springtest/AuthorityBindingTest.java` (`WebApplicationContextRunner` with the auto-configuration, the filter driven as
    `ContextAuthenticationFilterTest` does; the chain records the bound `AUTHORITY`):
    - `ID-02 a JwtAuthenticationToken binds AUTHORITY with its name, token value and expiry`: subject `alice`, the JWT's token value,
      `expiresAt()` equal to the JWT's `exp`;
    - `ID-02 a BearerTokenAuthentication binds AUTHORITY, and a token without an expiry gives an authority without one`: an opaque
      `OAuth2AccessToken` with and without `expiresAt` (ID-10);
    - `ID-02 an authentication that is not an OAuth2 token, or a request that is not authenticated, binds no AUTHORITY`:
      `UsernamePasswordAuthenticationToken.authenticated`, an `AnonymousAuthenticationToken`, no authentication; a JWT authentication as the
      red control;
    - `ID-02 AUTHORITY is bound from the authentication even when it is already bound`: an outer scope binds `Authority.of("svc", "x")`; the
      chain sees alice's (the IN-05 exception);
    - `ID-02 AUTHORITY is bound in the nested scope of the contributions, so the stored snapshot and an ASYNC dispatch hold it`: with a
      tenant contributor, `SNAPSHOT_ATTRIBUTE` holds both, and a `DeferredResult`'s `asyncDispatch` sees the same `Authority` instance; after
      the filter, `AUTHORITY` is unbound;
    - `ID-06 the bound authority is not in the MDC, nor in the snapshot filtered for DETACHED`: inside the chain `MDC` has `tenant` and no
      `boundrail.authority` entry, and `EffectivePolicy` filtering of `Context.capture()` for `DETACHED` lacks it, while the unfiltered
      capture has it (one test, paired with the red binding assertion);
    - `ID-02 without Spring Security's OAuth2 resource server no AUTHORITY is bound and contributors still run`:
      `FilteredClassLoader(AbstractOAuth2TokenAuthenticationToken.class)`: no `AuthorityExtraction` bean; a plain authenticated request still
      gets its contributed tenant.
  - Green: `spring/AuthorityExtraction.java`, its implementation in a nested configuration conditional on
    `AbstractOAuth2TokenAuthenticationToken`, and the binding step in `spring/ContextAuthenticationFilter.java`
    ([plan.md](plan.md#spring-wiring)).
  - Completes: ID-02 (binding, expiry, scope, IN-05 exception, non-OAuth2, without the resource server); ID-10 (from `getExpiresAt()`);
    ID-06 (MDC, `DETACHED`).
  - Verify: Unit `AuthorityBindingTest` and `ContextAuthenticationFilterTest`.
- [x] T005 [US1] `ContextContributions.put` rejects `AUTHORITY`.
  - Red, in `springtest/ContextAuthenticationFilterTest.java`:
    - `ID-02 ContextContributions.put throws IllegalArgumentException for AUTHORITY, after its null checks`: `put(AUTHORITY, authority)`
      throws `IllegalArgumentException`; `put(AUTHORITY, null)` throws `NullPointerException`; a raw `put(AUTHORITY, "text")` throws
      `IllegalArgumentException`, not `ClassCastException` (Q8); and the request's authority is still the authentication's;
    - `ID-02 a contributor's IllegalArgumentException propagates from the filter`: the chain is not run (a contributor error is an application
      bug, as CORE-13's exceptions are).
  - Green: the check in `Contributions.put` in `spring/ContextAuthenticationFilter.java`; the `@throws` in `spring/ContextContributions.java`.
  - Completes: ID-02 (`put`); CORE-16 (contribution check order).
  - Verify: Unit `ContextAuthenticationFilterTest`.
- [x] T006 [US1] Derive the initiator; enable IT-4.1 and IT-4.5; the ID-09 Javadoc.
  - Red:
    - in `springtest/InitiatorDerivationTest.java`:
      - `ID-08 an authenticated request binds INITIATOR user:<name>`: a JWT authentication gives `user:alice`; so does
        `UsernamePasswordAuthenticationToken.authenticated("alice", …)` (form login, HTTP Basic), which binds no authority;
      - `ID-08 a trusted Boundrail-Initiator wins over a contribution, and a contribution over the derived value`: trusted header
        `service:edge` + contributor `user:bob` → `service:edge`; contributor only → `user:bob`; neither → `user:alice` (one test, so the
        precedence assertions sit with the red derivation);
      - `ID-08 the derived initiator is in the stored snapshot, and a Callable runs with it`: `MockMvc` with a `Callable` handler records
        `user:alice` (IN-09, EXE-09);
    - in `springtest/ContextAuthenticationFilterTest.java` and `springtest/AsyncDispatchTest.java`: no change expected; both re-run (every
      authenticated request now enters the nested scope, [plan.md](plan.md#risks));
    - enable IT-4.1: alice's request to edge `/ping` with a marker; edge's observation has `ctx.authority-subject=alice` and
      `ctx.initiator=user:alice`;
    - enable IT-4.5: alice's request to edge `/authority/detached` with a marker; partner's observation has no `authorization` header;
      the task's recording has no authority and `detached-initiator=user:alice`.
  - Green:
    - the derivation in `spring/ContextAuthenticationFilter.java` ([plan.md](plan.md#spring-wiring));
    - topology test code: `ctx.authority-subject` and `ctx.initiator` in `topology/ObservedContext.java`; `/authority/detached` in
      `topology/EdgeApp.java`;
    - the ID-09 paragraph in the Javadoc of `core/BoundExecutors.java`: TDD exception, reviewed ([plan.md](plan.md#tdd-exceptions)).
  - Completes: ID-08 (derivation, precedence, non-OAuth2, `Callable`); ID-09 (Javadoc); IT-4.1; IT-4.5 (with ID-06 and POL-04).
  - Verify: Unit `InitiatorDerivationTest`, `ContextAuthenticationFilterTest` and `AsyncDispatchTest`; IT+.
- [x] T007 [US1] An invalid derived initiator; enable IT-4.4.
  - Red:
    - in `springtest/InitiatorDerivationTest.java`: `ID-08 a name longer than 251 characters leaves INITIATOR unbound, reports one
      INVALID_VALUE for boundrail.initiator with boundary INTERNAL_CALL and no destination, and the request continues`: a 252-character
      name; a 251-character name binds (256 with `user:`); the violation holds no value (DIAG-09); the chain runs and the authority is still
      bound;
    - in `springtest/InitiatorDerivationTest.java`: `ID-08 an empty name leaves INITIATOR unbound and reports one INVALID_VALUE for
      boundrail.initiator, while the authority is still bound` (ID-02, D66): a JWT with no `sub`; the authority has an empty subject;
    - in `springtest/InitiatorDerivationTest.java`: `ID-08 a null name is treated as empty: INITIATOR is unbound with one INVALID_VALUE
      for boundrail.initiator, and the authority has an empty subject` (ID-02, D66): a `BearerTokenAuthentication` subclass whose `getName()`
      returns `null`; added after T007's review;
    - enable IT-4.4: a request without a token to edge `/public/ping` with `Boundrail-Initiator: user:mallory` and a marker; status 200;
      the observation has neither `ctx.authority-subject` nor `ctx.initiator`; edge's `untrusted_header` counter rose by exactly one, both in
      total and for key `boundrail.initiator`. This is a guard: show it red with the derivation temporarily testing `authentication.isAuthenticated()` instead
      of the trust resolver (the anonymous token then gives `user:anonymousUser`), then revert ([plan.md](plan.md#guard-tests)).
  - Green: the `Initiator.of` failure path in `spring/ContextAuthenticationFilter.java`; topology test code: edge's `SecurityFilterChain`
    (`/public/**` permitted, the rest authenticated, JWT resource server) and `/public/ping` in `topology/EdgeApp.java`; a per-key violation
    helper in `topology/Topology.java`.
  - Completes: ID-08 (invalid derived value, unauthenticated); IT-4.4 (with ID-02, IN-02).
  - Verify: Unit `InitiatorDerivationTest`; IT+, plus `--tests '*TopologySmokeIT'` on both tasks (the chain changed).
- [x] T008 [US1] `AuthorityPropagator`, the forward propagator and the interceptor's ID-03 step; enable IT-4.2 and IT-4.7.
  - Red:
    - in `springtest/AuthorityPropagationTest.java` (`RecordingServer`, clients from the auto-configured `RestClient.Builder` and
      `RestTemplateBuilder`, an authority bound around the call):
      - `ID-03 an internal call carries Authorization: Bearer <credential>, and external and unknown calls carry none`: internal gets
        `Bearer secret-token`; `external()` and an undeclared unknown host get no `Authorization` (paired: ID-06's external clause);
      - `ID-03 with no AUTHORITY bound the propagator is not called and no Authorization is written`: a counting `AuthorityPropagator` bean;
      - `ID-03 an application-set Authorization is left unchanged and the propagator is not called, and a name with no values is unset`:
        the same client sends one call with `Authorization: Bearer app` (kept, counter unchanged), one with `authorization: x` (WIRE-06),
        one with the name present and no values (gets `Bearer secret-token`), one with `""` (kept, a value); and one with nothing (gets it);
      - `ID-03 a propagator that returns empty writes no Authorization and reports nothing`: a bean returning empty;
      - `ID-03 the propagator is called after the required-key check and after the deadline check`: unbound required `tenant` in `fail` mode
        throws `ContextViolationException` and an expired deadline throws `BEFORE_SEND`, each with the counter unchanged; in `report` mode
        the call carries `Authorization`;
      - `ID-03 a credential longer than wire.max-value-length is sent` (D60): a 1,000-character credential with the default 256 is sent
        whole, and no `OVERSIZED_VALUE` is reported;
      - `ID-04 the forward propagator is the default, and an AuthorityPropagator bean replaces it`: no bean → the bound credential; a bean
        returning `Optional.of("other")` → `Bearer other`, also with `authority.propagation=exchange` set and no exchange properties (not
        validated);
      - `CORE-16 the forward propagator rejects a null authority or destination with NullPointerException`: through the bean the
        auto-configuration creates;
      - `ID-04 with several AuthorityPropagator beans the @Primary one is used, and with none primary startup fails where HTTP client
        support is active, also with lazy initialization`: two beans, one `@Primary`: its credential is sent; none primary: startup fails,
        with and without lazy initialization; with `FilteredClassLoader(RestClientCustomizer.class)` (no client support) the same beans
        start (the customizers inject `AuthorityPropagator` directly, OUT-03's pattern);
    - replace `OUT-04 an internal call writes no Authorization` in `springtest/ContextClientInterceptorTest.java`, which ID-03 contradicts,
      by the ID-03 tests above;
    - enable IT-4.2: alice's request to the shared edge `/call-orders/as-caller` with a marker; orders' observation has
      `authorization=Bearer <alice's token>` and `ctx.initiator=user:alice`;
    - enable IT-4.7: alice's request to edge `/authority/call-orders/service-token` with a marker; orders' observation has exactly
      `authorization=Bearer service-token`. This is a guard: show it red with the interceptor's ID-03 step temporarily skipping the "already
      set" check, then revert ([plan.md](plan.md#guard-tests)).
  - Green: `core/AuthorityPropagator.java`; `spring/ForwardAuthorityPropagator.java`; the top-level `boundrailAuthorityPropagator` bean
    (forward only so far; `exchange` selected without a replacing bean is T010's) and the propagator passed through both customizers in
    `spring/BoundrailContextAutoConfiguration.java`; the ID-03 step in `spring/ContextClientInterceptor.java`
    ([plan.md](plan.md#spring-wiring)); topology test code: `/authority/call-orders/service-token` in `topology/EdgeApp.java`.
  - Completes: ID-03 (internal only, application-set, empty, ordering, D60); ID-04 (forward default, bean replaces, `@Primary`, none
    primary, lazy, without client support); ID-06 (external, unknown); CORE-16 (forward); the Configuration section's propagator failure;
    IT-4.2; IT-4.7.
  - Verify: Unit `AuthorityPropagationTest` and `ContextClientInterceptorTest`; IT+.
- [x] T009 [US1] WIRE-08 applies to the `Authorization` value.
  - Red, in `springtest/AuthorityPropagationTest.java`:
    - `ID-03 an Authorization value that is not a valid field value is not written, is reported once as INVALID_VALUE for boundrail.authority
      with INTERNAL_CALL and the destination, and the call continues` (parametrized): credentials with CR, LF, `é`, a trailing space, and
      `""` (giving `Bearer ` with a trailing space); the request is still sent; the violation holds no value (DIAG-09).
  - Green: the `isFieldValue` check on `"Bearer " + credential` in `spring/ContextClientInterceptor.java`.
  - Completes: ID-03 (WIRE-08).
  - Verify: Unit `AuthorityPropagationTest`.
**Checkpoint**: IT-4.1, IT-4.2, IT-4.4, IT-4.5 and IT-4.7 green on JDK 21 and JDK 25.

---

## Phase 4: User Story 2 - Token exchange for audience-scoped tokens (Priority: P2)

**Goal**: The `exchange` propagator trades the caller's token for one scoped to the destination service, caches it, and fails cleanly.

**Independent Test**: IT-4.3 and IT-4.8 in the IT command.

- [x] T010 [US2] The exchange request and a valid response.
  - Red:
    - Jackson 3 for `-spring` (decision B2): the catalog alias `jackson-databind = { module = "tools.jackson.core:jackson-databind" }`
      (no version, BOM-managed) in `gradle/libs.versions.toml`, and `compileOnly(libs.jackson.databind)` and
      `testImplementation(libs.jackson.databind)` in `boundrail-context-spring/build.gradle.kts`;
    - in `springtest/support/RecordingServer.java`: answering with a given status, body and `Content-Type`, and recording request bodies
      (test support);
    - in `springtest/TokenExchangeTest.java` (one `RecordingServer` as the token endpoint answering `{"access_token":"exchanged-orders"}`
      with 200, another as orders; `authority.propagation=exchange` with its three properties):
      - `ID-11 an internal call sends one form POST to token-uri with the RFC 8693 fields and audience, and carries the returned token`:
        the token endpoint received `POST`, `Content-Type: application/x-www-form-urlencoded`, the form `grant_type=urn:ietf:params:oauth:
        grant-type:token-exchange`, `subject_token=<credential>`, `subject_token_type=urn:ietf:params:oauth:token-type:access_token`,
        `audience=orders`; orders received `Authorization: Bearer exchanged-orders`;
      - `ID-11 the client authenticates with HTTP Basic, each part form-encoded as RFC 6749 section 2.3.1 defines`: client id `a b:c` and
        secret `p&ß` give `Basic base64("a+b%3Ac:p%26%C3%9F")`;
      - `ID-11 token_type and Content-Type are not checked` (Q4): `token_type` absent or `mac`, and a response without `Content-Type`, still
        give the token;
      - `ID-11 the exchange request carries no context headers and does not pass through the library's interceptor`: the token endpoint
        received no `Tenant-Id`, `Boundrail-Initiator` or `Boundrail-Deadline-Ms`, and no `Authorization: Bearer`;
      - `ID-11 a body that is JSON null is not a token: the call is not sent`: the token endpoint answers 200 with `null` (Jackson gives
        a `null` map, T001); the client call throws and orders receives nothing, never `Bearer null` (T013 pins the exception type);
      - `CORE-16 the exchange propagator rejects a null authority or destination with NullPointerException`;
      - `RT-09 the exchange propagator's HTTP client is closed when the context closes`: after the context's `close()`, the propagator's
        `HttpClient` reports `isTerminated()`.
  - Green: `spring/ExchangeAuthorityPropagator.java` (request; response parsed with its own strict Jackson 3 `JsonMapper`, not the application's
    mapper (`USE_BIG_DECIMAL_FOR_FLOATS` and `USE_BIG_INTEGER_FOR_INTS` come with `expires_in` in T012); a non-object result rejected; no cache yet),
    its selection by `authority.propagation=exchange` in `spring/BoundrailContextAutoConfiguration.java` ([plan.md](plan.md#spring-wiring)).
  - Completes: ID-11 (request, valid response, whole-body `null`); ID-04 (`exchange` selection); CORE-16 (exchange).
  - Verify: Unit `TokenExchangeTest`.
- [x] T011 [US2] Exchange settings are validated at startup.
  - Red, in `springtest/TokenExchangeConfigurationTest.java`:
    - `ID-11 with the built-in exchange propagator a missing, blank, relative, non-HTTP or host-less token-uri fails startup, also with lazy
      initialization` (parametrized): unset, ``, `   `, `/token`, `ftp://h/token`, `http:foo` and `http:///x` (Q5), `not a uri`,
      `https://edge:pw@host/token` (userinfo, D66; the failure message never contains it);
      `HTTPS://h/token` starts;
    - `ID-11 a missing or blank client-id or client-secret fails startup, also with lazy initialization`;
    - `ID-06 the startup failure names the property and never contains the client secret`: the failure's message chain lacks the secret;
    - `ID-04 with an AuthorityPropagator bean, or with forward, the exchange properties are not validated`: both start with
      `token-uri=/relative` and no client id;
    - `ID-11 with the built-in exchange propagator and no Jackson 3, startup fails naming Jackson 3, also with lazy initialization; forward or
      an AuthorityPropagator bean start without it`: `FilteredClassLoader("tools.jackson")` with valid exchange properties: the failure's
      message names Jackson 3 (`tools.jackson.databind`) and not the secret, with and without lazy initialization; with `forward`, and with
      `exchange` plus a replacing bean, the same class loader starts (one test, so the negatives sit with the red failure).
  - Green: the validation in the `boundrailAuthorityPropagator` bean method, `@Lazy(false)`: the Jackson 3 presence check through the bean
    class loader before `ExchangeAuthorityPropagator` is loaded, and `getHost() == null` for Q5 rather than `HttpRequest.newBuilder` (whose
    message echoes the URI and its userinfo) ([plan.md](plan.md#spring-wiring)).
  - Completes: ID-11 (startup validation, Jackson 3 required); ID-04 (not validated when replaced); ID-06 (startup messages); the
    Configuration section's exchange failures.
  - Verify: Unit `TokenExchangeConfigurationTest`.
- [ ] T012 [US2] Cache exchanged tokens; enable IT-4.3.
  - Red:
    - in `springtest/TokenExchangeCacheTest.java` (fixed `Clock`, the propagator called directly with a counting token endpoint):
      - `ID-04 a second call for the same credential and audience uses the cached token, and another audience or credential exchanges`:
        endpoint calls 1, then 2 for `billing`, 3 for another credential;
      - `ID-04 a cached token is used until 30 seconds before it expires`: `expires_in=3600`: cached at +3569s, exchanged again at +3570s;
      - `ID-04 a cached token is never used beyond the source credential's expiry`: authority expiring in 60s, `expires_in=3600`: exchanged
        again at +60s; an authority without expiry is limited by `expires_in` only (ID-10);
      - `ID-11 a response without expires_in, or one whose lifetime is 30 seconds or less, is used and not cached`: `expires_in` absent,
        `30`, `0`: each call exchanges; and a source expiry already passed: not cached;
      - `ID-11 an expires_in too large for the clock is valid and the cache end is capped` (Q3, B2): `1e400` and
        `99999999999999999999` with an authority expiring in 60s: cached, exchanged again at +60s; with an authority without expiry: cached,
        no exception (end `Instant.MAX`);
      - `ID-11 the cache holds at most 10,000 entries and evicts the entry added earliest`: 10,001 audiences; the first is exchanged again,
        the second is not; an expired entry replaced by a new exchange counts as added last;
      - `ID-11 cache keys hold the SHA-256 hash of the credential, never the credential`: `cacheKeys()` holds the hex SHA-256 and the
        audience, and their `toString()` lacks the credential;
    - enable IT-4.3: alice's request to the exchange edge `/authority/call-orders/twice` with a marker; orders' two observations both have
      `authorization=Bearer ` + the stub's `issued` token for audience `orders`, never alice's token; the stub's calls rose by exactly one,
      and its recording has `form.audience=orders`, `form.subject_token=<alice's token>` and `authorization=Basic <edge:edge-secret>`.
  - Green: the cache in `spring/ExchangeAuthorityPropagator.java`, and its mapper's `USE_BIG_DECIMAL_FOR_FLOATS` and
    `USE_BIG_INTEGER_FOR_INTS` for `expires_in` ([plan.md](plan.md#spring-wiring)); topology test code: the stub's JSON
    answer and recordings in `topology/TokenExchangeApp.java`; `jti` in `topology/TestJwts.java`; `/authority/call-orders/twice` in
    `topology/EdgeApp.java`; the exchange edge in `it/AuthorityPropagationIT.java` ([plan.md](plan.md#integration-tests)).
  - Completes: ID-04 (cache); ID-11 (cache lifetime, size, keys); IT-4.3; SC-003.
  - Verify: Unit `TokenExchangeCacheTest`; IT+.
- [ ] T013 [US2] Failed exchanges throw `TokenExchangeException`.
  - Red, in `springtest/TokenExchangeFailureTest.java` (`OutputCaptureExtension`):
    - `ID-12 a status other than 200, or a body that is not valid, throws TokenExchangeException and the request is not sent` (parametrized
      over T001's findings): 201, 302, 400, 500; an empty body, `not json`, a whole-body `null`, a JSON array, a truncated
      `{"access_token":"t`, trailing garbage `{"access_token":"t"} trailing`; `access_token` missing, `null`, `""`, `42`, `["t"]`;
      `expires_in` of `-1`, `1.5`, `"3600"`, `null`, `true`, `NaN`; a duplicate `access_token` key (Q9, D65);
      orders receives nothing; the exception has no cause (the parse exception is dropped, B2); `expires_in` of `3600.0` and `1e3` are
      valid (Q3);
    - `ID-12 an unreachable token endpoint throws TokenExchangeException with the I/O exception as its cause, and the request is not sent`:
      a refused port (`ConnectException`);
    - `ID-12 an interrupted exchange throws TokenExchangeException and keeps the interrupt status` (Q6): the calling thread is interrupted
      before the call; the cause is the `InterruptedException`, `Thread.interrupted()` is `true` afterwards (which also clears it for the
      next test), and orders receives nothing;
    - `ID-12 no violation is reported for a failed exchange`;
    - `ID-12 the message names the audience and the status, and contains no credential, exchanged token, client secret or body`: for a 500
      whose body holds a token-like string, and for a 200 body whose parse error would echo a secret (an unquoted
      `{"access_token":SECRET-xyz}`, which Jackson reports as `Unrecognized token 'SECRET-xyz'`); and `ID-06` in the same test: the
      exception's message, every message in its cause chain, and the captured output contain none of them;
    - `ID-12 TokenExchangeException has no public constructor and reaches RestClient and RestTemplate callers unwrapped`.
  - Green: `spring/TokenExchangeException.java`; the validity checks and failure paths in `spring/ExchangeAuthorityPropagator.java`
    (`IOException` and `InterruptedException` mapped here; `HttpTimeoutException` gets its own branch, before `IOException`, in T014).
  - Completes: ID-12 (failures, message); ID-11 (response validity); ID-06 (exception messages, logs).
  - Verify: Unit `TokenExchangeFailureTest`.
- [ ] T014 [US2] The exchange's timeout.
  - Red, in `springtest/TokenExchangeFailureTest.java` (a slow `RecordingServer`: it accepts and delays its response, so the JDK client
    throws `HttpTimeoutException`, T001; the no-deadline timeout passed to the package-private constructor as 300ms):
    - `ID-12 with a deadline bound, an exchange slower than the remaining time throws DeadlineExceededException at TIMEOUT, counted once,
      and the request is not sent`: a 200ms deadline and a 3s delay: `TIMEOUT` within 200ms plus 200ms, cause `HttpTimeoutException` (Q7),
      `boundrail.context.deadline.exceeded{stage=timeout}` at 1;
    - `ID-12 a deadline that has run out when the exchange starts throws TIMEOUT without calling the token endpoint` (Q2): the endpoint
      received nothing, counter at 1, and no `IllegalArgumentException` from the request builder;
    - `ID-12 with no deadline bound, an exchange slower than the fixed timeout throws TokenExchangeException`: 300ms timeout, 3s delay;
      cause `HttpTimeoutException`; no counter;
    - only if the sandbox lets a connect to a non-routable address hang (try `10.255.255.1` first; otherwise leave the test out and say so
      in the task report): `ID-12 a connect slower than the timeout is a timeout`: with a deadline bound, `TIMEOUT` with an
      `HttpConnectTimeoutException` cause;
    - `ID-12 the fixed timeout is 10 seconds and has no property`: the bean the auto-configuration creates reports 10s.
  - Green: in `spring/ExchangeAuthorityPropagator.java`, the timeout selection with the "run out" check before `timeout(...)` is set (the
    builder rejects zero and negative durations), and the `HttpTimeoutException` branch caught before the general `IOException`.
  - Completes: ID-12 (timeouts).
  - Verify: Unit `TokenExchangeFailureTest`.
- [ ] T015 [US2] Exchange without an audience; enable IT-4.8 and IT-4.6.
  - Red:
    - in `springtest/TokenExchangeTest.java`: `ID-05 a destination without a service name gets no Authorization, the token endpoint is not
      called, and one MISSING_AUDIENCE is reported for boundrail.authority with INTERNAL_CALL and the destination`: an undeclared client to a
      host matching an internal pattern; the request is still sent;
    - enable IT-4.8: alice's request to the exchange edge `/authority/call-orders/undeclared` with a marker; orders' observation has no
      `authorization`; the stub's calls unchanged; the exchange edge's `missing_audience` counter rose by exactly one;
    - enable IT-4.6, with `OutputCaptureExtension` and `@BoundrailTest`: it sends the requests of IT-4.1 – IT-4.5, IT-4.7 and IT-4.8 (shared
      and exchange edge) with one alice token, then asserts that the captured output, the recorded `ctx.mdc` and `ctx.snapshot` entries,
      and every recorded violation's `toString()` never contain that token, and (precondition) that edge recorded
      `ctx.authority-subject=alice` with them. This is a guard: show it red with (a) `Authority.toString()` temporarily printing the
      credential and (b) `MdcScopeListener` temporarily not skipping `AUTHORITY`, then revert ([plan.md](plan.md#guard-tests)).
  - Green: the `MISSING_AUDIENCE` path in `spring/ExchangeAuthorityPropagator.java`; topology test code: `/authority/call-orders/undeclared`
    and the `ctx.mdc`/`ctx.snapshot` recordings in `topology/EdgeApp.java`.
  - Completes: ID-05; IT-4.8; IT-4.6 (with ID-01, ID-06, LOG-02); SC-002.
  - Verify: Unit `TokenExchangeTest`; IT+.

**Checkpoint**: IT-4.1 – IT-4.8 green on JDK 21 and JDK 25.

---

## Phase 5: User Story 3 - Authority never leaks (Priority: P1)

Delivered inside the tasks above, because each guarantee is the negative side of a positive test there: IT-4.5 in T006, IT-4.6 in T015;
ID-06's unit clauses in T003 (properties), T004 (MDC, `DETACHED`), T008 (external, unknown), T011 (startup messages) and T013 (exception
messages, logs). The `MESSAGE` clause is IT-4.9 in 007-kafka.

---

## Phase 6: Close

- [ ] T016 Verify each requirement one by one against the code and its tests, using the coverage table in
  [plan.md](plan.md#requirement-coverage):
  - Requirements: CORE-16, ID-02 – ID-06, ID-08 – ID-12.
  - SC-001: AuthorityPropagationIT has no `@Disabled` left except IT-4.9 (`Pending: M6`); IT-4.1 – IT-4.8 pass in `integrationTestJdk21` and
    `integrationTestJdk25`.
  - SC-002: IT-4.6. SC-003: IT-4.3 and T012's unit tests.
  - Every requirement has a test whose display name starts with its ID, except ID-09's Javadoc: check the Javadoc of `BoundExecutors` against
    ID-09, word for word in meaning.
  - The Configuration section's startup failures each have a test with lazy initialization (T003, T008, T011).
  - `boundrail-context-core` still has no runtime dependencies
    (`./gradlew :boundrail-context-core:dependencies --configuration runtimeClasspath`); Spring Security, Micrometer and Jackson 3 stay
    `compileOnly` in `-spring` (`./gradlew :boundrail-context-spring:dependencies --configuration runtimeClasspath`).
  - By inspection: only `AuthorityExtraction`'s implementation names an OAuth2 resource-server type, and only `ExchangeAuthorityPropagator`
    imports `tools.jackson`; nothing new is static (RT-09); the
    exchange request never goes through a Spring client; no log statement or exception message in the new code takes a credential, token,
    secret or body; `AUTHORITY` is still never written to the MDC (LOG-02).
  - spec.md's Status, roadmap.md's M5 and the README, if it lists milestones, are updated by the spec-creator, not here.

  Run `./gradlew check` fresh, which covers JDK 21, JDK 25 and the forced ThreadLocal run. With the CI run green, this closes the feature.

## Dependencies & Execution Order

- T001 is done; decision B2 settles the parser (Jackson 3, required for `exchange`). Q1 – Q9 are closed ([plan.md](plan.md#spec-gaps)).
- Foundational: T002 and T003 are independent of each other and of T001.
- US1: T004 needs T002. T005 needs T004 (same file). T006 needs T004. T007 needs T006. T008 needs T002 and T006 (IT-4.2 checks the
  initiator). T009 needs T008.
- US2: T010 needs T001, T003 and T008. T011 needs T010. T012 needs T010 (and T011, so the exchange edge's settings are validated). T013 needs
  T010. T014 needs T013. T015 needs T012 (the exchange edge) and T008.
- T016 comes last.
- Tasks touching the same file run in sequence (`ContextAuthenticationFilter`, `ContextClientInterceptor`,
  `BoundrailContextAutoConfiguration`, `ExchangeAuthorityPropagator`, `EdgeApp` and `AuthorityPropagationIT` are touched by several).
- Every task is committed and reviewed on its own, on branch `006-identity`.

## Exit Checklist

- [x] Q1 – Q8 settled by the user (2026-10-13)
- [x] Q1 – Q8 written into spec.md and design.md by the spec-creator (D64)
- [x] T001's outcome recorded in plan.md, and any choice it raises settled by the user (B2, 2026-10-13)
- [x] Q9 settled by the user and written into spec.md (D65)
- [ ] Scenarios enabled and green on JDK 21 and JDK 25: IT-4.1 – IT-4.8
- [ ] Requirements implemented with tests named after their IDs (roadmap traceability row M5): CORE-16, ID-02 – ID-06, ID-08, ID-10 – ID-12;
  the Javadoc statement of ID-09 checked by inspection (TDD exception)
- [ ] Guard scenarios (IT-4.4 in T007, IT-4.7 in T008, IT-4.6 in T015) shown red against their temporary change, and the change reverted
- [ ] `boundrail-context-core` still has no runtime dependencies; Spring Security, Micrometer and Jackson 3 are `compileOnly` in `-spring`
- [ ] `./gradlew check` green
