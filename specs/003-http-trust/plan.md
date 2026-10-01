# Implementation Plan: HTTP Boundaries and Trust

**Branch**: `003-http-trust` | **Date**: 2026-10-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-http-trust/spec.md`, contract [contracts/http-api.md](contracts/http-api.md)

**Status**: Draft for approval. The spec is "Ready for planning": the earlier gaps G1 – G18, DESIGN Q6 and the ambiguities A1 – A8 are settled
in spec.md, the contract and design.md (D35). No spec gap is open and no task is blocked.

## Summary

Context crosses HTTP only where the policy and the trust rules allow.

- `boundrail-context-core`:
  - the transport-neutral destination model, and `HostPatternClassifier` with pattern validation (DEST-01 – DEST-04);
  - the `Violation` record with its six kinds, and `ContextViolationException` (DIAG-01, DIAG-02);
  - `ContextPolicy.keys()` (POL-10).
- `boundrail-context-spring`, one auto-configuration that:
  - merges every `ContextPolicy` bean with the built-in policy (POL-07);
  - reports violations as a rate-limited WARN log and a counter in the application's own `MeterRegistry`, never with the value (DIAG-03,
    DIAG-09);
  - installs an around-style `ClientHttpRequestInterceptor` on every auto-configured `RestClient.Builder` and `RestTemplateBuilder`. It
    classifies the destination, checks required keys, writes or strips wire headers, and enforces the length and field-value rules (OUT, WIRE,
    POL-08, DIAG-02);
  - registers `ContextInboundFilter` before Spring Security: trust verdict (default, CIDR or bean), decode, bind, repeated and untrusted headers
    (IN-01 – IN-03, IN-08);
  - registers `ContextAuthenticationFilter` after it: contributors, `ContextContributions.invalid`, `JwtClaimContributor` (IN-04 – IN-07).
- `boundrail-context-test`: `TestInboundTrustVerifier` (TEST-01).

Library code is driven by unit and `ApplicationContextRunner` tests in core and `-spring`. Each TrustBoundaryIT scenario is enabled in the task
whose code makes it pass, so it is that task's failing acceptance test where it can be. Three scenarios (IT-2.2, IT-2.3, IT-2.6) and thirteen
unit tests already pass before their task; each red is shown against a temporary code change that is then reverted ([Guard Tests](#guard-tests)).

## Technical Context

**Language/Version**: Java 21 (`--release 21`) in `-spring` and `-test`; nothing new in core's `java25` source set

**Primary Dependencies**:

- core: none (BLD-03).
- `-spring`: Spring Boot 4.1.1 (`spring-boot-autoconfigure`, already `implementation`). `compileOnly` and conditional: `spring-web`,
  `jakarta.servlet-api`, `spring-boot-restclient` (Boot 4 home of `RestClientCustomizer`, `RestTemplateBuilder` and `RestTemplateCustomizer`),
  `spring-security-web` (present), `spring-security-oauth2-resource-server` and `spring-security-oauth2-jose` (`JwtAuthenticationToken`, `Jwt`),
  `micrometer-core` (present).
- `-test`: `compileOnly` on `-spring`, `spring-web` and `jakarta.servlet-api`.
- `integration-tests`: `spring-boot-starter-restclient`, `micrometer-core`, `spring-boot-test` (`OutputCaptureExtension`).

**Storage**: N/A

**Testing**:

- core unit tests;
- `-spring` tests with `ApplicationContextRunner` and `WebApplicationContextRunner`, `MockHttpServletRequest` and `MockFilterChain` (spring-test,
  through `spring-boot-starter-test`);
- real `RestClient` calls to a JDK `com.sun.net.httpserver.HttpServer` started by the test (`springtest/support/RecordingServer`), with no mocked
  HTTP client (BLD-07);
- TrustBoundaryIT in `integration-tests` on `integrationTestJdk21` and `integrationTestJdk25` (`integrationTestJdk25ThreadLocal` runs only
  RuntimeConformanceIT).

**Target Platform**: JVM 21+, servlet-based Spring MVC with `RestClient` and `RestTemplate` (spec Assumptions; no WebFlux or WebClient)

**Project Type**: library (Spring Boot auto-configuration)

**Performance Goals**: none stated; the interceptor and filters do one policy lookup per key and no reflection per request

**Constraints**:

- DEST types in core with no HTTP dependency (DEST-01).
- Every adapter is around-style (IN-01, OUT-01).
- Spring Security, Micrometer, the servlet API, `spring-web` and `spring-boot-restclient` are only `compileOnly`, behind `@ConditionalOnClass` on
  separate nested configuration classes.
- No DNS lookup when parsing CIDR entries (IN-08).
- No work from M3 – M6: no deadline header in or out, no authority, no initiator derivation, no executors, no MDC.

**Scale/Scope**: 12 contract types plus `ContextPolicy.keys()`, 33 requirements, 9 scenarios; 29 tasks (T001 – T028 and T006a) and a close task

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|---|---|---|
| I Scope-only | Pass | Both filters run the rest of the chain inside `ContextBinding.call` (IN-01, IN-04); the interceptor binds nothing; no setter |
| II Declared propagation | Pass | Outbound writes only keys the policy lists for the classified boundary (OUT-04); inbound binds only keys listing `INTERNAL_CALL` (IN-02) |
| III Secure by default | Pass | Default verifier trusts nobody (IN-03); strict CIDRs (IN-08); `UNKNOWN` = `EXTERNAL` (DEST-03); forbidden headers stripped (WIRE-07); no value in any report (DIAG-09); invalid field values never written (WIRE-08) |
| IV Declared, never guessed | N/A | No deadlines or executors in this feature (OUT-04 writes no `DEADLINE`) |
| V Evidence only | Pass | `Violation` holds kind, key, boundary, destination and thread only; log entries name key, wire name and boundary, never the value or a codec message (DIAG-09, D35) |
| VI Interoperate | Pass | Counter only in a unique or primary `MeterRegistry` bean, never the global registry (DIAG-03); decorates Boot's builders through customizers |
| VII Spec-first, test-first | Pass | Every task is one red-green cycle; sixteen guard tests ([Guard Tests](#guard-tests)) need the user's acceptance; every behaviour planned is in spec.md, including the A1 – A8 resolutions |
| VIII Simplicity | Pass | One auto-configuration class with nested conditional classes, one interceptor, two filters, one reporter, two built-in verifiers; no SPI beyond the contract |
| Technical constraints | Pass | Core stays dependency-free (checked in T029); optional libraries `compileOnly`; Boot 4.x |

## Research Notes

### Build

- **Build changes go in the task whose red needs them, so they are not TDD exceptions.** As 002 put the `java25` compile classpath in T009, each
  dependency is added in the green step of the first task whose failing test cannot compile or run without it:
  - `-spring`: T006 (`spring-boot-starter-test`), T007 (`micrometer-core` for tests), T008 (`spring-web`, `spring-boot-restclient`), T016
    (`jakarta.servlet-api`, `spring-boot-security` for tests), T025 (`spring-security-web` for tests), T027 (OAuth2 resource server and JOSE);
  - `-test`: T020;
  - `integration-tests`: T013 (`spring-boot-starter-restclient`), T014 (`micrometer-core`), T024 (`spring-boot-test`).

  Version catalog entries are added in the same tasks, with versions managed by the Boot BOM. `spring-boot-restclient` is not in the local
  Gradle cache, so the first build of T008 needs network access; T008 checks the module name against the Boot 4.1.1 BOM.
- **The auto-configuration imports file has a failing check.** T006 writes `AutoConfigurationImportsTest`, which loads
  `ImportCandidates.load(AutoConfiguration.class, classLoader)` and expects `BoundrailContextAutoConfiguration`. It fails until
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` lists the class, so the resource file is not a TDD
  exception.

### Core

- **Policy keys (POL-10).** `keys()` returns `Set.copyOf(rules.keySet())`. Every built policy already holds the built-in rules (POL-06), and
  `merge` already unions the rules maps, so no other change is needed.
- **Destination classification (DEST-02).** `HostPatternClassifier.of` copies both lists (rejecting `null`), then validates (DEST-04). `classify` returns:
  1. the declared destination, if present;
  2. else `Destination(INTERNAL, empty)` on an internal match;
  3. else `Destination(EXTERNAL, empty)` on an external match;
  4. else `Destination(UNKNOWN, empty)`.

  Hosts and patterns compare with `toLowerCase(Locale.ROOT)`. `*.suffix` matches a host that ends with `.suffix` and has at least one non-empty
  label before it. A URI without a host matches no pattern and is `UNKNOWN`.
- **Host pattern validation (DEST-04).** One check per pattern: strip a leading `*.`, then the rest is at most 253 characters and is split on `.`.
  Each label must match `[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?`, and the last label must not be all digits. Empty labels (`a..b`, a
  trailing dot) fail the label rule. CORE-03's precedence applies through CORE-13: a `null` list or element throws `NullPointerException` even
  when another pattern is invalid.
- **Violation and its exception.** `Violation` is a record with `Objects.requireNonNull` on every component (CORE-13).
  `ContextViolationException(Violation)` is a `RuntimeException`. Its message is built from the violation's kind, key name and boundary only, so it
  cannot carry a value either (DIAG-09).

### Spring wiring

- **Effective policy (POL-07).** The merged policy cannot itself be a `ContextPolicy` bean, or it would be one of the beans it merges. It is held
  by a package-private `EffectivePolicy` bean: `BuiltInKeys.defaultPolicy()` merged, in bean order, with every `ContextPolicy` bean
  (`ObjectProvider.orderedStream()`). `merge` fails on conflicts (POL-05), so a conflicting pair fails startup with `IllegalStateException` as the
  root cause.
  - Ancestor contexts count too: `orderedStream()` resolves through `beanNamesForTypeIncludingAncestors`, so a parent context's
    `ContextPolicy` beans are merged (T006a).
  - The bean is `@Lazy(false)`, so it is built at startup even with `spring.main.lazy-initialization=true`.
    `LazyInitializationBeanFactoryPostProcessor` leaves a bean whose lazy flag is set explicitly unchanged (T006a).
  - `ApplicationContextRunner` does not run `SpringApplication`, so the property alone changes nothing in a runner test. T006a's test adds
    `LazyInitializationBeanFactoryPostProcessor` through `withInitializer`, as `SpringApplication` does for that property, and sets the property
    too.
- **Configuration.** One `@ConfigurationProperties("boundrail.context")` record, `BoundrailContextProperties`, with nested records:
  - `Violations`: `mode`, an enum `FAIL`/`REPORT`, and `logInterval`, a `Duration`;
  - `Inbound`: `trust`, an enum `NONE`/`CIDR`, and `trustedCidrs`;
  - `Destinations`: `internalHosts` and `externalHosts`;
  - `Wire`: `maxValueLength`, default 256.

  Each nested record is added by the first task that reads it. Defaults are those of the spec's Configuration table, and that task asserts them.
- **Violation reporting (DIAG-03, DIAG-09).** Package-private `ViolationReporter` in `-spring`:
  - `report(Violation)` increments the counter `boundrail.context.violations` with tags `kind`, `key` and `boundary`, in lower case
    (`Locale.ROOT`). It uses `ObjectProvider<MeterRegistry>.getIfUnique()`, which returns the only bean or the primary one, and is otherwise empty.
    It never touches `Metrics.globalRegistry`.
  - It logs at WARN through Commons Logging (`org.apache.commons.logging.Log`, Spring's own logging API), at most once per (kind, key, boundary)
    per `log-interval`.
  - The log entry holds the kind, the key name, the key's wire name when the policy gives it one (looked up through `EffectivePolicy` and
    `keys()`), the boundary, the destination and the thread. It never holds a value or an exception message.
  - A package-private constructor takes the `Log` and a `java.time.Clock`, so tests record entries and move time without sleeping.
  - The Micrometer part is a nested `@ConditionalOnClass(MeterRegistry.class)` configuration that supplies a counter function, so
    `ViolationReporter` never names a Micrometer type.
- **Violation fields in M2.** `thread` is `Thread.currentThread().getName()`. `captureSite` and `executorEvidence` are always empty (004).
  Outbound violations carry the classified `Destination`. Inbound and authentication violations carry boundary `INTERNAL_CALL` and no
  destination (DIAG-08).

### Outbound

- **Per-client declaration (OUT-03).** `BoundrailClients.internal(service)` returns `builder -> builder.defaultRequest(spec ->
  spec.attribute(ATTRIBUTE, destination))`. The interceptor reads the attribute from `HttpRequest.getAttributes()` (Spring Framework 7).
  `RestTemplate` requests have no declaration and are classified by host only.
- **Interceptor (OUT-01 – OUT-04, WIRE-01, WIRE-02, WIRE-04, WIRE-07, WIRE-08, POL-08).** Package-private `ContextClientInterceptor`, added by a
  `RestClientCustomizer` bean and a `RestTemplateCustomizer` bean in a nested `@ConditionalOnClass` configuration. Per request:
  1. Classify the request. The boundary is `INTERNAL_CALL` for `INTERNAL`, else `EXTERNAL_CALL`.
  2. Required-key check (POL-08, DIAG-02): collect the keys of `policy.keys()` that are required, propagate to the boundary, and are not bound
     (`key.isBound()`; a default does not count, CORE-05), sorted by `name()` with `String.compareTo`. Report one `MISSING_REQUIRED` for each,
     in that order. In `fail` mode, then throw one `ContextViolationException` carrying the first, before any header is written or `execute`
     is called. In `report` mode, continue.
  3. Write headers. For each key of `policy.filter(Context.capture(), boundary)` that has a wire name, except `AUTHORITY` and `DEADLINE`:
     - skip it if the request already has that header (`HttpHeaders` is case-insensitive);
     - encode it: the key's codec, or `Initiator.value()` for `INITIATOR` (WIRE-02). Nothing catches an exception from `encode`; it reaches
       the caller unchanged (OUT-04, WIRE-05);
     - if the encoding's `String.length()` (UTF-16 code units) is greater than `wire.max-value-length`, report `OVERSIZED_VALUE` and skip it
       (WIRE-04);
     - else if it is not a valid field value, report `INVALID_VALUE` and skip it (WIRE-08);
     - else set the header.
  4. For `EXTERNAL` and `UNKNOWN`, remove every header whose name is the wire name of a policy key that does not propagate to `EXTERNAL_CALL`, with
     one `FORBIDDEN_HEADER` per removed name, however many values it had (WIRE-07). `HttpHeaders` holds all values of a name, compared
     case-insensitively, under one entry. `Authorization` is never a policy wire name (POL-09).
  5. Call `execution.execute` exactly once and return its response.
- **Field value check (WIRE-08).** A value is valid if it is empty, or if every character is in U+0020 – U+007E or is U+0009, and the first and
  last characters are in U+0021 – U+007E. Written as a loop over `charAt`, not a regex.

### Inbound

- **Inbound filter (IN-01, IN-02, WIRE-04 – WIRE-06).** `ContextInboundFilter extends OncePerRequestFilter`, registered through a
  `FilterRegistrationBean` with order `-101`. That is one before Boot's `SecurityFilterProperties.DEFAULT_FILTER_ORDER` (`-100`). The constant is
  copied, because Spring Security is optional, and a test compares it with Boot's constant; T016 checks the class's Boot 4 location. The filter
  asks the verifier, then:
  - For `TRUSTED_INTERNAL`, for each key of `policy.keys()` that propagates to `INTERNAL_CALL` and has a wire name, except `AUTHORITY` and
    `DEADLINE`:
    1. read `Collections.list(request.getHeaders(wireName))`; servlet header names are case-insensitive (WIRE-06);
    2. none: skip the key; more than one: report `INVALID_VALUE` and decode none (IN-02);
    3. one: check the length first (WIRE-04), then decode with the key's codec, or `Initiator.of` for `INITIATOR` (WIRE-02);
    4. a decode failure, an `IllegalArgumentException` or subclass from `decode`, is `INVALID_VALUE`, with neither the value nor the
       exception's message (WIRE-05, DIAG-09). Nothing else is caught: any other exception propagates out of the filter unchanged;
    5. add the value to one `ContextBinding`.
  - For `UNTRUSTED`, report one `UNTRUSTED_HEADER` for each key of `policy.keys()` that has a wire name and whose `getHeaders` is non-empty,
    and bind nothing (IN-02). This covers every key, including `DEADLINE` and keys that do not propagate to `INTERNAL_CALL`; `AUTHORITY` has
    no wire name, so `Authorization` is never reported.

  It then runs `filterChain.doFilter` inside `binding.call(...)`, rethrowing `IOException` and `ServletException` unchanged (CORE-08).
- **Trust verifiers (IN-03, IN-08).** Both built-in verifiers are `@ConditionalOnMissingBean(InboundTrustVerifier.class)`, so an application bean
  replaces them.
  - The default bean returns `UNTRUSTED`.
  - With `inbound.trust=cidr`, the package-private `CidrInboundTrustVerifier` parses `trusted-cidrs` once at bean creation, so an invalid entry
    fails startup.
  - JDK 21 has no `InetAddress.ofLiteral`, so each address, entry or remote address, is first checked to be textually an IPv4 dotted quad or
    an IPv6 literal (hex digits, `:` and `.`, no `%`). Only then is it passed to `InetAddress.getByName`, which does no lookup for a literal.
    Hostnames and zone IDs are rejected without DNS: an invalid entry fails startup, and a remote address that fails the check matches no entry,
    so the verdict is `UNTRUSTED` (IN-03, IN-08).
  - `getByName` turns `::ffff:a.b.c.d` into an `Inet4Address`. So an IPv6-text entry that yields an `Inet4Address` is an IPv4-mapped entry and is
    rejected, and an IPv4-mapped remote address naturally matches IPv4 entries.
  - Host bits are checked by masking. A bare address gets `/32` or `/128`.
  - Matching compares only addresses of the same family.
- **Authentication filter (IN-04, IN-05, IN-07).** `ContextAuthenticationFilter` is in a nested configuration, `@ConditionalOnClass` on Spring
  Security's `SecurityContextHolder`.
  - It is registered with order `-99`, after the `springSecurityFilterChain` registration. A servlet filter after the security filter chain runs
    inside `FilterChainProxy`'s call to the original chain, so the `SecurityContext` is populated there.
  - It reads `SecurityContextHolder.getContext().getAuthentication()`. The request is authenticated when that authentication is not `null`,
    `isAuthenticated()`, and `new AuthenticationTrustResolverImpl().isAuthenticated(authentication)` (not anonymous).
  - It calls each `ContextContributor` bean in `ObjectProvider.orderedStream()` order, with a package-private `ContextContributions`:
    - `put` checks for `null` and does `key.type().cast(value)` (CORE-13);
    - it keeps the first contribution per key, and skips keys already bound (`key.isBound()`, IN-05);
    - `invalid(key)` reports `INVALID_VALUE` with boundary `INTERNAL_CALL` and no destination (IN-07, DIAG-08).
  - It runs the rest of the chain in a nested binding of the kept values. IT-2.5 proves the order in a running service (roadmap risk).
- **JwtClaimContributor (IN-06).** It contributes only for a `JwtAuthenticationToken`. It reads `jwt.getClaims().get(claim)`:
  - absent: nothing happens;
  - not a `String`: `invalid(key)`;
  - else decoded with the codec; an `IllegalArgumentException` or subclass gives `invalid(key)`, and any other exception propagates (WIRE-05).

  It applies no length check (D35).
- **TestInboundTrustVerifier (TEST-01).** In `-test`:
  - a secret generated once per JVM (`SecureRandom`, 32 bytes, Base64URL) in a `static final` field, so every instance and every service of the
    in-JVM topology share it;
  - `verify` returns `TRUSTED_INTERNAL` exactly when `getHeaders("Boundrail-Test-Peer")` has one value and it equals the secret
    (`MessageDigest.isEqual`);
  - `peer()` returns `builder -> builder.defaultHeader("Boundrail-Test-Peer", SECRET)`;
  - nothing in the module is in an imports file.

### Integration tests

- **Topology (001 Test Topology), test code added by the first task that needs each part.**
  - T013: under `topology/`, `AppKeys` (`tenant`, `priority`) and `TopologyPolicy`, the topology table's policy as a `ContextPolicy` bean on
    every service; `boundrail.context.destinations.internal-hosts=*.svc.test` on every service; and `EdgeApp` endpoints that call orders and
    partner through internal, external and undeclared `RestClient`s built from edge's auto-configured `RestClient.Builder`. Edge is a resource
    server, so the test sends alice's JWT to every edge endpoint.
  - T014: a `SimpleMeterRegistry` bean per service, and `Running.violations(Service, Violation.Kind)`, which sums the counter
    `boundrail.context.violations` with tag `kind` on that service.
  - T020: the `TestInboundTrustVerifier` bean on every service, `peer()` on edge's internal clients only, and `ctx.tenant` and `ctx.priority`
    (the values of `find()`, absent if unbound) recorded by every `/ping`.
  - T027: edge's `JwtClaimContributor` bean and `TestJwts.withoutTenant()`.

  Counters and observations are cumulative across the shared topology, so each scenario asserts a delta around its own request.
- **IT-2.8 and DIAG-09.** The scenario uses Spring Boot's `OutputCaptureExtension`. It asserts that the violation log entries for `priority` and
  `tenant` are present, and that neither they nor the counter's tags contain `abc` or the 300-character value. The `Violation` records
  themselves are not observable from the topology; their content is asserted in the unit tests of WIRE-04 (T011, T023) and WIRE-05 (T024), as
  DIAG-09 says.

### Verify commands

Unit: `./gradlew :<project>:test --tests '<Class>'`. Scenarios (Gradle applies `--tests` to the task just before it):

```bash
./gradlew --continue :integration-tests:integrationTestJdk21 --tests '*TrustBoundaryIT' \
          :integration-tests:integrationTestJdk25 --tests '*TrustBoundaryIT'
```

`integrationTestJdk25ThreadLocal` is not listed, because its filter runs only RuntimeConformanceIT. Every task ends with `./gradlew check`.

## Spec Gaps

None open. The ambiguities A1 – A8 of the earlier draft are decided in spec.md (IN-02, IN-03, IN-08, WIRE-04, WIRE-05, WIRE-07, POL-08, DIAG-02,
DIAG-09), the contract and design.md D35, and their tests are planned in T010, T011, T014, T015, T019, T021, T024 and T028. A8 needs no task:
M2 owes only the spec's Configuration table (IN-03).

P7 (group and package, M7) does not block: the work uses `io.github.boundrail.context`, as 002 did. Q1 – Q5 do not apply to M2; Q6 is resolved
(D35).

## Guard Tests

These tests already pass before the task that introduces them, usually because they assert that something does not happen and nothing would make
it happen yet. As in 002 (review point I-1), each red is shown against a temporary code change, which is reverted before the commit. They are not
TDD exceptions, but the user is asked to accept them with the plan.

A test that is green before its task is accepted as a guard when its red is shown against a named temporary change to the finished code, which
is then reverted; the leader reports it in the task report, the planner adds it to this table, and the reviewer checks it.

Every other negative assertion in this plan sits in the same test as a positive assertion that is red before its task. For example, "binds only
the initiator" asserts the initiator is bound and the deadline is not.

| Task | Scenario or test | Why green first | Red shown against (temporary, reverted) |
|---|---|---|---|
| T001, rewritten in T003 | `DEST-02 a URI whose host does not parse is UNKNOWN` | `URI.getHost()` is already `null` for `orders_svc`, and T001's `classify` treats a `null` host as matching no pattern. T003 makes the pattern `orders_svc` invalid (DEST-04), so it rewrites the test to the pattern `*.svc.test` and the URI `http://orders_x.svc.test:8080/`, whose `getHost()` is also `null`, and shows it red again | In `HostPatternClassifier.classify`, the URI's raw authority host used when `getHost()` is `null` (both in T001 and in T003) |
| T001 | `DEST-02 of copies its lists` | T001's `of` copies both lists in the same green step that makes the CORE-13 test pass | In `HostPatternClassifier.of`, the caller's lists kept instead of copied, still rejecting `null` elements so the CORE-13 test stays green |
| T002 | `DEST-02 a wildcard does not match the bare suffix` | T001's exact matching already rejects `svc.test` and `xsvc.test` for the pattern `*.svc.test` | In `HostPatternClassifier.classify`, a naive wildcard that matches with `host.endsWith(pattern.substring(2))`, with no leading dot |
| T003 | `DEST-04 valid host patterns are accepted` | Before T003, `of` accepts every pattern | In `HostPatternClassifier.of`, the 253-character limit counted over the whole pattern including `*.`, so `*.` followed by a 253-character name is rejected |
| T003 | `CORE-13 a null pattern list is rejected before pattern validation` | `List.copyOf` already throws `NullPointerException` for a `null` list | In `HostPatternClassifier.of`, the internal list validated before the external list is copied, so an `IllegalArgumentException` is thrown instead |
| T005 | `POL-10 keys() is unmodifiable` | A `Set.of()` stub, the minimum `keys()` before the other POL-10 tests, cannot be changed either | `keys()` returning `new HashSet<>(rules.keySet())` |
| T006a | `DEST-02 a host with a trailing dot is UNKNOWN` | T002's matching compares the whole host, and no valid pattern ends with a dot (DEST-04), so `orders.svc.test.` matches neither `orders.svc.test` nor `*.svc.test` | In `HostPatternClassifier.classify`, one trailing dot stripped from the host before matching |
| T006a | `POL-07 ContextPolicy beans in a parent context are merged` | T006's `ObjectProvider<ContextPolicy>.orderedStream()` searches ancestor contexts, so the parent's bean is already merged | In `boundrailEffectivePolicy`, the beans collected with the local-only `ListableBeanFactory.getBeansOfType(ContextPolicy.class)` instead |
| T013 | IT-2.2 External call carries no context | After T010, the interceptor writes nothing to `EXTERNAL` | In `ContextClientInterceptor`, the classified destination replaced by `Destination(INTERNAL, empty)` |
| T013 | IT-2.3 Unknown means external | After T010, the interceptor writes nothing to `UNKNOWN` | The same change |
| T027 | IT-2.6 Forged tenant with no claim | No code binds `tenant` from an untrusted header, and alice's claim is absent | Edge's verifier bean replaced by one returning `TRUSTED_INTERNAL` for every request |
| T012 | `WIRE-04 an outbound value both oversized and not a valid field value is reported once, as OVERSIZED_VALUE` | T011's length check already drops the value before any character check exists | In `ContextClientInterceptor`, the WIRE-08 check run before the WIRE-04 check |
| T024 | `WIRE-04 an inbound value both oversized and undecodable is reported once, as OVERSIZED_VALUE, without calling the codec` | T023's length check already drops the value before decoding | In `ContextInboundFilter`, `decode` called before the length check |
| T024 | `WIRE-05 any other exception from decode propagates unchanged and no violation is reported` | Before T024 nothing catches an exception from `decode`, so every exception propagates | In `ContextInboundFilter`, the catch widened from `IllegalArgumentException` to `RuntimeException` |
| T028 | `IN-06 a claim value longer than max-value-length is bound` | No length check exists on the authentication path | In `ContextAuthenticationFilter`, contributed values longer than `wire.max-value-length` dropped |
| T028 | `IN-06 any other exception from the claim's codec propagates unchanged` | T027's `JwtClaimContributor` catches nothing, so every exception propagates | In `JwtClaimContributor`, the catch widened from `IllegalArgumentException` to `RuntimeException` |

## TDD Exceptions

None proposed. Every dependency, version catalog entry and the auto-configuration imports file is part of the green step of a task with a
failing test ([Research Notes](#build)). There is nothing for the user to approve in this table.

| Task | What | Verified by |
|---|---|---|
| none | | |

## Project Structure

### Documentation (this feature)

```text
specs/003-http-trust/
├── spec.md
├── plan.md                # this file
├── tasks.md
└── contracts/http-api.md
```

### Source Code (repository root)

```text
gradle/libs.versions.toml                                             # catalog entries (T006, T008, T013, T016, T020, T024, T027)
boundrail-context-core/src/main/java/io/github/boundrail/context/
├── Destination.java  OutboundRequest.java  DestinationClassifier.java  HostPatternClassifier.java     # T001 – T003
├── Violation.java  ContextViolationException.java                                                    # T004
└── ContextPolicy.java                                                                               # T005 (keys())
boundrail-context-core/src/test/java/io/github/boundrail/context/
├── HostPatternClassifierTest.java  ViolationTest.java  ContextPolicyTest.java (extended)
boundrail-context-spring/build.gradle.kts                                                            # T006, T007, T008, T016, T025, T027
boundrail-context-spring/src/main/java/io/github/boundrail/context/spring/
├── BoundrailContextAutoConfiguration.java  BoundrailContextProperties.java  EffectivePolicy.java (package-private)
├── ViolationReporter.java (package-private)
├── ContextClientInterceptor.java (package-private)  BoundrailClients.java
├── InboundTrustVerifier.java  CidrInboundTrustVerifier.java (package-private)  ContextInboundFilter.java
├── ContextAuthenticationFilter.java  ContextContributor.java  ContextContributions.java  JwtClaimContributor.java
boundrail-context-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports   # T006
boundrail-context-spring/src/test/java/io/github/boundrail/context/spring/
├── AutoConfigurationImportsTest.java  EffectivePolicyTest.java  ViolationReporterTest.java
├── ClientRegistrationTest.java  BoundrailClientsTest.java  ContextClientInterceptorTest.java
├── ContextInboundFilterTest.java  InboundTrustVerifierTest.java  CidrInboundTrustVerifierTest.java
├── ContextAuthenticationFilterTest.java  JwtClaimContributorTest.java
├── support/RecordingServer.java (JDK HttpServer recording requests)  support/RecordingLog.java (Commons Logging Log recording entries)
boundrail-context-test/build.gradle.kts                                                              # T020
boundrail-context-test/src/main/java/io/github/boundrail/context/test/TestInboundTrustVerifier.java   # T020
boundrail-context-test/src/test/java/io/github/boundrail/context/test/TestInboundTrustVerifierTest.java
integration-tests/build.gradle.kts                                                                   # T013, T014, T024
integration-tests/src/test/java/io/github/boundrail/context/it/TrustBoundaryIT.java                  # IT-2.1 – IT-2.9 enabled
integration-tests/src/test/java/io/github/boundrail/context/it/topology/
├── AppKeys.java  TopologyPolicy.java (new)
├── Topology.java  ObservedContext.java  EdgeApp.java  OrdersApp.java  PartnerApp.java  BillingApp.java  TokenExchangeApp.java  TestJwts.java
```

**Structure Decision**: Destination and violation types go in core's base package, as DEST-01 and the contract require. Everything Spring goes
in `io.github.boundrail.context.spring`. Types the contract names are public; the rest are package-private where Spring allows it. The test
module holds only `TestInboundTrustVerifier`. TrustBoundaryIT stays in `integration-tests`, the only project that runs against packaged JARs.

## Requirement Coverage

| Requirement or scenario | Tasks |
|---|---|
| CORE-13 | T001 (DEST types), T003 (`null` before invalid pattern), T004 (`Violation`, exception), T009 (`BoundrailClients`), T016 (default verifier), T020 (test verifier), T021 (CIDR verifier), T025 (`put`, wrong type), T026 (`invalid`), T027 (`JwtClaimContributor`) |
| POL-07 | T006, T006a (parent context merged; eager under lazy initialization) |
| POL-08 | T015 (one violation per unbound required key) |
| POL-10 | T005 |
| DEST-01 | T001, T029 (no HTTP dependency in core) |
| DEST-02 | T001, T002, T006a (trailing dot), T009 |
| DEST-03 | T010 (writing), T014 (stripping) |
| DEST-04 | T003, T009 (startup failure) |
| WIRE-01 | T010 (outbound), T016 (inbound) |
| WIRE-02 | T010 (initiator out), T017 (initiator in; deadline and authority not bound) |
| WIRE-04 | T011 (outbound, UTF-16 code units), T012 (before WIRE-08), T023 (inbound), T024 (before decode), T028 (not on claims) |
| WIRE-05 | T010 (`encode` exceptions propagate), T024 (decode failures, other `decode` exceptions propagate), T028 (claims) |
| WIRE-06 | T010, T014 (outbound), T016, T018, T019 (inbound) |
| WIRE-07 | T014 (one violation per removed name) |
| WIRE-08 | T012 |
| IN-01 | T016 |
| IN-02 | T016, T017, T018, T019 |
| IN-03 | T016 (default, bean replaces), T021 (CIDR, `getRemoteAddr()`, non-literal remote address) |
| IN-04 | T025 |
| IN-05 | T025 |
| IN-06 | T027 (valid, missing, non-JWT), T028 (non-string, undecodable, no length check, other codec exceptions) |
| IN-07 | T026 |
| IN-08 | T021 (address families, IPv4-mapped remote, non-literal and zone-ID remote addresses, no DNS), T022 (strict entries, startup failure, no DNS) |
| OUT-01 | T008 |
| OUT-02, OUT-03 | T009 |
| OUT-04 | T010, T014 |
| DIAG-01 | T004 |
| DIAG-02 | T004 (exception), T015 (report, then throw; several keys in name order, exception carries the first); report-only kinds under the default `fail` mode in T011, T012, T014, T018, T019, T023, T024, T026 |
| DIAG-03 | T007 |
| DIAG-08 | T018, T019, T023, T024, T026, T028 |
| DIAG-09 | T007 (log entry content), T011, T012, T023, T024, T028 (`Violation` records); IT-2.8 (log entries, counter tags) |
| TEST-01 | T020 |
| IT-2.1 | T027 |
| IT-2.2, IT-2.3 | T013 (guard) |
| IT-2.4 | T014 |
| IT-2.5 | T027 |
| IT-2.6 | T027 (guard) |
| IT-2.7 | T020 |
| IT-2.8 | T024 |
| IT-2.9 | T015 |
| SC-001 – SC-003 | T029 |

## Risks

- **Filter ordering relative to Spring Security** (roadmap M2). The inbound filter must run before the security chain, and the authentication
  filter after it, inside `FilterChainProxy`. Unit tests compare orders with Boot's constant; IT-2.5 proves it in a running service.
- **Exceptions from interceptors.** IT-2.9 needs `ContextViolationException`, and OUT-04 an exception from `encode`, to reach the caller
  unchanged through `RestClient`. `RestClient` wraps only `IOException`. T010 and T015 check this, and debug to the root cause if it is
  wrapped.
- **Interceptor order.** Application interceptors added after the library's can still add headers after stripping (WIRE-07). This is out of
  scope for the IDs, and recorded as a review note in T014.
- **Rate-limited log entries in IT-2.8.** The log entry appears only on the first occurrence per (kind, key, boundary) per minute on orders. No
  other scenario raises `INVALID_VALUE` for `priority` or `OVERSIZED_VALUE` for `tenant` on orders. If a later suite shares the JVM and does,
  IT-2.8's presence assertion would need its own service or interval.
- **Shared JVM topology.** Five services share the JVM and the per-run secret, and counters are per service. Scenarios assert deltas, never
  totals.

## Complexity Tracking

No constitution violations to justify.
