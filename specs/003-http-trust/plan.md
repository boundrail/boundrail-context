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
- `boundrail-context-test`: `TestInboundTrustVerifier` (TEST-01), with `-spring` as an `api` dependency (D37).

Library code is driven by unit and `ApplicationContextRunner` tests in core and `-spring`. Each TrustBoundaryIT scenario is enabled in the task
whose code makes it pass, so it is that task's failing acceptance test where it can be. Three scenarios (IT-2.2, IT-2.3, IT-2.6), T020a's added
IT-2.2 and IT-2.3 assertions, thirty-eight unit tests and one BuildSmokeIT test already pass before their task; each red is shown against a
temporary code change that is then reverted ([Guard Tests](#guard-tests)).

## Technical Context

**Language/Version**: Java 21 (`--release 21`) in `-spring` and `-test`; nothing new in core's `java25` source set

**Primary Dependencies**:

- core: none (BLD-03).
- `-spring`: Spring Boot 4.1.1 (`spring-boot-autoconfigure`, already `implementation`). `compileOnly` and conditional: `spring-web`,
  `jakarta.servlet-api`, `spring-boot-restclient` (Boot 4 home of `RestClientCustomizer`, `RestTemplateBuilder` and `RestTemplateCustomizer`),
  `spring-security-web` (present), `spring-security-oauth2-resource-server` and `spring-security-oauth2-jose` (`JwtAuthenticationToken`, `Jwt`),
  `micrometer-core` (present).
- `-test`: `api` on `-spring` (T020a; `compileOnly` in T020); `compileOnly` on `spring-web` and `jakarta.servlet-api` (TEST-01, D37).
- `integration-tests`: `spring-boot-starter-restclient`, `micrometer-core`, `spring-boot-test` (`OutputCaptureExtension`); the generated POM and
  module metadata of `-test` (T020a).

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

**Scale/Scope**: 12 contract types plus `ContextPolicy.keys()`, 33 requirements, 9 scenarios; 31 tasks (T001 – T028, T006a, T015a and T020a) and a close task

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
| VII Spec-first, test-first | Pass | Every task is one red-green cycle; fifty-two guard tests ([Guard Tests](#guard-tests)) need the user's acceptance; every behaviour planned is in spec.md, including the A1 – A8 resolutions |
| VIII Simplicity | Pass | One auto-configuration class with nested conditional classes, one interceptor, two filters, one reporter, two built-in verifiers; no SPI beyond the contract |
| Technical constraints | Pass | Core stays dependency-free (checked in T029); optional libraries `compileOnly`; Boot 4.x |

## Research Notes

### Build

- **Build changes go in the task whose red needs them, so they are not TDD exceptions.** As 002 put the `java25` compile classpath in T009, each
  dependency is added in the green step of the first task whose failing test cannot compile or run without it:
  - `-spring`: T006 (`spring-boot-starter-test`), T007 (`micrometer-core` for tests), T008 (`spring-web`, `spring-boot-restclient`), T016
    (`jakarta.servlet-api`, `spring-boot-security` for tests), T025 (`spring-security-web` for tests), T027 (OAuth2 resource server and JOSE);
  - `-test`: T020, T020a (`-spring` as `api`; `maven-publish`);
  - `integration-tests`: T013 (`spring-boot-starter-restclient`), T014 (`micrometer-core`), T020a (POM and module metadata of `-test`),
    T024 (`spring-boot-test`).

  Version catalog entries are added in the same tasks, with versions managed by the Boot BOM. `spring-boot-restclient` is not in the local
  Gradle cache, so the first build of T008 needs network access; T008 checks the module name against the Boot 4.1.1 BOM.
- **The auto-configuration imports file has a failing check.** T006 writes `AutoConfigurationImportsTest`, which loads
  `ImportCandidates.load(AutoConfiguration.class, classLoader)` and expects `BoundrailContextAutoConfiguration`. It fails until
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` lists the class, so the resource file is not a TDD
  exception.
- **The `-test` dependency scopes have a failing check (TEST-01, D37).** Nothing in the build generates a POM or module metadata today, and no
  project applies a publishing plugin. T020a applies Gradle's built-in `maven-publish` to `boundrail-context-test` only, with one publication
  `maven` from the `java` component, so that `generatePomFileForMavenPublication` and `generateMetadataFileForMavenPublication` write
  `build/publications/maven/pom-default.xml` and `module.json`. Nothing is published, and this does not settle Q5 (Maven Central tooling, M7).
  The integration test tasks depend on both generate tasks, declare the files as inputs, and pass their paths as the system properties
  `boundrail.it.test-module.pom` and `boundrail.it.test-module.metadata`, as they already pass `boundrail.it.task`. BuildSmokeIT's TEST-01 test
  is red until `-spring` is `api`, so this harness and the scope change are not TDD exceptions.
  - No `group` or `version` is set (P7 is open until M7), so the POM's `groupId` is empty and its version `unspecified`; the test asserts
    artifact IDs and scopes only. If a generate task refuses the empty group, T020a stops and reports it rather than choosing a group.
  - In the POM, an `api` dependency has scope `compile`; in `module.json`, it is listed under the `apiElements` variant. `compileOnly`
    dependencies appear in neither.

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
- **Invalid configuration under lazy initialization (POL-07, DEST-04, IN-08, DIAG-03, IN-01, IN-04).** Every invalid-configuration startup failure
  also occurs with `spring.main.lazy-initialization=true`. Each bean whose creation validates configuration is `@Lazy(false)`:
  - the effective policy (POL-07, T006a);
  - the `boundrailDestinationClassifier` bean (DEST-04, T009);
  - the `boundrailViolationReporter` bean (DIAG-03, T010);
  - the `RestClientCustomizer` and `RestTemplateCustomizer` beans in `ClientConfiguration` (OUT-03, T010);
  - the built-in verifier bean (IN-03, IN-08, T022);
  - the `boundrailInboundFilter` registration bean (IN-01, T018);
  - the `boundrailAuthenticationFilter` registration bean, where Spring Security is on the classpath (IN-04, `Integer.MAX_VALUE`, T025).

  An invalid `spring.security.filter.order` fails startup (IN-01, user decision from the T017 review). The registration bean reads it from the
  `Environment` as an `Integer`, so `abc` already fails the conversion (`ConversionFailedException`, root cause `NumberFormatException`), and
  rejects `Integer.MIN_VALUE`, at which `securityOrder - 1` would wrap to `Integer.MAX_VALUE`, with `IllegalArgumentException`. Up to T017 the
  registration bean is not `@Lazy(false)`. In a real servlet application the embedded server fetches every `ServletContextInitializer` bean
  during refresh, but a `WebApplicationContextRunner` starts no server, so under lazy initialization the bean, and the property, would never
  be read. T018 makes it `@Lazy(false)`, like the other validating beans, so both failures occur at startup in either case.

  A negative `log-interval` is rejected by the `Violations` compact constructor when `BoundrailContextProperties` is bound (DIAG-03). The
  default classifier bean binds it, but an application `DestinationClassifier` bean replaces that bean (`@ConditionalOnMissingBean`), and then
  nothing eager binds the properties. So the reporter, which reads `log-interval`, is `@Lazy(false)` (T010). Each test uses the T006a setup.

  A `wire.max-value-length` less than 1 is rejected the same way, by the `Wire` compact constructor (`IllegalArgumentException`) when
  `BoundrailContextProperties` is bound (WIRE-04, user decision). The `@Lazy(false)` reporter already binds the properties at startup, so
  nothing else is needed under lazy initialization (T012).

  An invalid `violations.mode`, such as `bogus`, fails the binding of `BoundrailContextProperties` (DIAG-02, user decision), so the same
  `@Lazy(false)` reporter makes it fail startup under lazy initialization. Boot's lenient enum conversion already matches `fail` and `report`
  ignoring case, and converts an empty value to `null`, which the binder replaces with `@DefaultValue("fail")`. The current code is expected to
  pass all three T015a tests, so they are guards; if one is red, its green is the minimum change to `Violations` (T015a).

  `inbound.trust` follows the same rules (IN-03, user decision from the T021 review). Boot's lenient enum conversion trims the value,
  ignores case and non-alphanumeric characters, and converts an empty or blank value to `null`, which `@DefaultValue("none")` replaces. An
  invalid value, such as `bogus`, fails the binding of `BoundrailContextProperties`, which the `@Lazy(false)` reporter and client customizers
  bind at startup, with or without lazy initialization and whether or not a verifier bean exists. T021's two `@ConditionalOnProperty`
  verifier beans compare the raw property with `equalsIgnoreCase`, so ` Cidr ` and a present empty value match neither and leave no verifier.
  T022 replaces them with one bean method that switches on the bound `Trust` (see Inbound). `withPropertyValues` trims values, so T022's
  tests set ` Cidr ` and `" "` through a `MapPropertySource`.
- **Configuration.** One `@ConfigurationProperties("boundrail.context")` record, `BoundrailContextProperties`, with nested records:
  - `Violations`: `mode`, an enum `FAIL`/`REPORT`, and `logInterval`, a `Duration`;
  - `Inbound`: `trust`, an enum `NONE`/`CIDR`, and `trustedCidrs`;
  - `Destinations`: `internalHosts` and `externalHosts`;
  - `Wire`: `maxValueLength`, default 256, at least 1 (T012).

  Each nested record is added by the first task that reads it. Defaults are those of the spec's Configuration table, and that task asserts them.
- **Violation reporting (DIAG-03, DIAG-09).** Package-private `ViolationReporter` in `-spring`:
  - `report(Violation)` increments the counter `boundrail.context.violations` with tags `kind`, `key` and `boundary`, in lower case
    (`Locale.ROOT`). It uses `ObjectProvider<MeterRegistry>.getIfUnique()`, which returns the only bean or the primary one, and is otherwise empty.
    It never touches `Metrics.globalRegistry`.
  - It logs at WARN through Commons Logging (`org.apache.commons.logging.Log`, Spring's own logging API), at most once per (kind, key, boundary)
    per `log-interval`. A repeat is logged when `now` is not before the last entry plus `log-interval`, so `0s` logs every occurrence. A
    negative `log-interval` fails startup: the `Violations` compact constructor throws `IllegalArgumentException`. Both are user decisions
    (DIAG-03).
  - `Violation.key` is always the policy key's name (`ContextKey.name()`, e.g. `tenant`, `boundrail.initiator`), never the header name as
    received or as written (user decision, DIAG-01). The reporter's counter tag `key` and the log entry's key name come from it, and the wire
    name is looked up from it. Every later task that builds a violation (T011, T012, T014, T015, T018, T019, T023, T024, T026 – T028) passes the
    key's name, also when the violation is found through a header, as in `FORBIDDEN_HEADER` and `UNTRUSTED_HEADER`.
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
  - `defaultRequest` composes its consumers with `andThen`, so when both `internal(..)` and `external()` are applied to one builder, the
    later one overwrites the attribute and wins (OUT-03, T010 guard).
  - Applied to a builder the library didn't customize, such as `RestClient.builder()`, the attribute is set but no interceptor reads it, so
    the declaration has no effect (OUT-03). This is documented in the contract's Javadoc only; it has no test.
- **Several classifier beans (OUT-03).** Each customizer injects a single `DestinationClassifier`: Spring resolves the `@Primary` bean, and
  with several and none primary the customizer bean fails with `NoUniqueBeanDefinitionException`, so startup fails (T010 guards).
  - Under lazy initialization the customizers are only created with the first `RestClient.Builder` or `RestTemplateBuilder`, so startup
    would succeed. Both customizer beans are `@Lazy(false)`, so the failure occurs at startup whenever client support is active (user
    decision; T010).
  - Without client support (`ClientConfiguration`'s `@ConditionalOnClass` not met) no bean injects a single `DestinationClassifier`: the
    default classifier is absent because application beans exist, and the reporter does not use a classifier. Startup succeeds, as OUT-03
    requires. Nothing has to be built for this, so it has no test.
- **Interceptor (OUT-01 – OUT-04, WIRE-01, WIRE-02, WIRE-04, WIRE-07, WIRE-08, POL-08).** Package-private `ContextClientInterceptor`, added by a
  `RestClientCustomizer` bean and a `RestTemplateCustomizer` bean in a nested `@ConditionalOnClass` configuration. Each customizer adds it
  at most once per client: it skips a `RestTemplate` or `RestClient.Builder` whose interceptors already include a `ContextClientInterceptor`
  (`noneMatch(ContextClientInterceptor.class::isInstance)`). Per request:
  1. Classify the request. The boundary is `INTERNAL_CALL` for `INTERNAL`, else `EXTERNAL_CALL`.
  2. Required-key check (POL-08, DIAG-02): collect the keys of `policy.keys()` that are required, propagate to the boundary, and are not bound
     (`key.isBound()`; a default does not count, CORE-05), sorted by `name()` with `String.compareTo`. Report one `MISSING_REQUIRED` for each,
     in that order. In `fail` mode, then throw one `ContextViolationException` carrying the first, before any header is written or `execute`
     is called. In `report` mode, continue. A header that application code set for an unbound required key is not touched by this step, and
     step 3 writes nothing for an unbound key, so in `report` mode the application's header is sent (DIAG-02, user decision, T015a).
  3. Write headers. For each key of `policy.filter(Context.capture(), boundary)` that has a wire name, except `AUTHORITY` and `DEADLINE`:
     - skip it if application code already set that header, on every boundary (OUT-04). A header is set only when its name has at least one
       value: `!getOrEmpty(wireName).isEmpty()`, not `containsHeader`, so a name with no values is unset and gets the context value, and
       `""` counts as a value (T011). `HttpHeaders` is case-insensitive. The skip comes before encoding, so an application-set key's
       `encode` is never called and its context value gets neither the WIRE-04 nor the WIRE-08 check (T011, T012);
     - encode it: the key's codec, or `Initiator.value()` for `INITIATOR` (WIRE-02). Nothing catches an exception from `encode`; it reaches
       the caller unchanged (OUT-04, WIRE-05);
     - if the encoding's `String.length()` (UTF-16 code units) is greater than `wire.max-value-length`, report `OVERSIZED_VALUE` and skip it
       (WIRE-04);
     - else if it is not a valid field value, report `INVALID_VALUE` and skip it (WIRE-08);
     - else set the header.
  4. For `EXTERNAL` and `UNKNOWN`, remove every header whose name is the wire name of a policy key that does not propagate to `EXTERNAL_CALL`, with
     one `FORBIDDEN_HEADER` per removed name that had at least one value, however many (WIRE-07). A removed name with no values is not
     reported (T014). `HttpHeaders` holds all values of a name, compared case-insensitively, under one entry. `Authorization` is never a
     policy wire name (POL-09). A header of a key that propagates to `EXTERNAL_CALL` is not touched, so an application-set value is sent
     unchanged (OUT-04, T011).
  5. Call `execution.execute` exactly once and return its response.
- **Field value check (WIRE-08).** A value is valid if it is empty, or if every character is in U+0020 – U+007E or is U+0009, and the first and
  last characters are in U+0021 – U+007E. Written as a loop over `charAt`, not a regex.

### Inbound

- **Inbound filter (IN-01, IN-02, WIRE-04 – WIRE-06).** `ContextInboundFilter extends OncePerRequestFilter`, registered through a
  `FilterRegistrationBean` in a nested `@ConditionalOnWebApplication(type = SERVLET)` configuration (T017 tests that nothing is registered
  outside a servlet application). Its order is one below `spring.security.filter.order`, read from the `Environment` with a default of `-100`
  (Boot's `SecurityFilterProperties.DEFAULT_FILTER_ORDER`), so `-101` by default (T016) and `-201` at `-200` (T017, user decision). A value that
  is not an integer, or is `Integer.MIN_VALUE`, fails startup (T018, user decision; see Spring wiring). The default is copied, because Spring Security is optional, and a test compares the order with Boot's constant; T016 checks the class's Boot 4 location.
  The filter asks the verifier, then:
  - For `TRUSTED_INTERNAL`, for each key of `policy.keys()` that propagates to `INTERNAL_CALL` and has a wire name, except `AUTHORITY` and
    `DEADLINE`, which are skipped before step 1, so a repeated `Boundrail-Deadline-Ms` is not reported (T018):
    1. read `Collections.list(request.getHeaders(wireName))`; servlet header names are case-insensitive (WIRE-06);
    2. none: skip the key; more than one: report `INVALID_VALUE` and decode none (IN-02);
    3. one: check the length first (WIRE-04), then decode with the key's codec, or `Initiator.of` for `INITIATOR` (WIRE-02);
    4. a decode failure, an `IllegalArgumentException` or subclass from `decode`, is `INVALID_VALUE`, with neither the value nor the
       exception's message (WIRE-05, DIAG-09). Nothing else is caught: any other exception propagates out of the filter unchanged;
    5. add the value to one `ContextBinding`.
  - For `UNTRUSTED`, report one `UNTRUSTED_HEADER` for each key of `policy.keys()` that has a wire name and whose `getHeaders` is non-empty,
    and bind nothing (IN-02). This covers every key, including `DEADLINE` and keys that do not propagate to `INTERNAL_CALL`; `AUTHORITY` has
    no wire name, so `Authorization` is never reported.

  It then runs `filterChain.doFilter` inside `binding.call(...)`, rethrowing `IOException` and `ServletException` unchanged (CORE-08). Any
  other checked exception, which the chain can only sneaky-throw, is rethrown unchanged through a generic sneaky-rethrow helper, as on the
  path with nothing bound, and not wrapped in `UndeclaredThrowableException` (T017, T016 review). The helper is `private` (T018, T017 review).
- **Trust verifiers (IN-03, IN-08).** From T022, one bean method, `boundrailInboundTrustVerifier`, switches on the bound `Trust`. It is
  `@ConditionalOnMissingBean(InboundTrustVerifier.class)`, so an application bean replaces the built-in verifier, and `@Lazy(false)` (user
  decision from the T021 review, replacing T021's two `@ConditionalOnProperty` beans).
  - `NONE` returns the default verifier, which returns `UNTRUSTED`.
  - `CIDR` returns the package-private `CidrInboundTrustVerifier`, which parses `trusted-cidrs` once at construction, so an invalid entry
    fails startup. The bean is `@Lazy(false)`, so this holds under lazy initialization too. Entries are parsed only in the `CIDR` branch, so
    with `none` they are ignored (T022).
  - JDK 21 has no `InetAddress.ofLiteral`, so each address, entry or remote address, is first checked to be textually an IPv4 dotted quad or
    an IPv6 literal (hex digits, `:` and `.`, no `%`). Only then is it passed to `InetAddress.getByName`, which does no lookup for a literal.
    Hostnames and zone IDs are rejected without DNS: an invalid entry fails startup, and a remote address that fails the check matches no entry,
    so the verdict is `UNTRUSTED` (IN-03, IN-08).
  - The two no-lookup tests, for entries and for the remote address, observe lookups directly (IN-08, T022 review). A rejected result is not
    proof enough: with entries passed straight to `getByName`, `localhost` was resolved through DNS and still failed startup, because the
    mapped-entry check rejects the resulting `Inet4Address` after the lookup. So the tests use a test-scoped
    `java.net.spi.InetAddressResolverProvider`, `springtest/support/RecordingResolverProvider.java`, registered in
    `boundrail-context-spring/src/test/resources/META-INF/services/java.net.spi.InetAddressResolverProvider`.
    - The JDK loads the provider once per JVM, at the first lookup, and uses it for every test in the module's test JVM. So it delegates
      every forward and reverse lookup to the built-in resolver unchanged, except names under the reserved `boundrail-dns-probe.invalid`
      (RFC 6761). It records those in a thread-safe set and answers them with `UnknownHostException` itself, so no probe reaches the network.
    - `InetAddress` caches answers, failures included, so each test makes a fresh name, `<random UUID>.boundrail-dns-probe.invalid`. It
      asserts that the name is not recorded, then runs a positive control: a direct `InetAddress.getByName` of the same name throws
      `UnknownHostException` and the name is now recorded. The control runs after the assertion, so the name is never cached before it.
    - If the control fails because the JDK chose its resolver before the provider was visible, the leader stops and reports it
      (`systematic-debugging`); the test is not weakened.
  - A remote address longer than 45 characters, the longest textual literal, is rejected before any pattern runs (T021). T022 removes the
    brackets before that check, with `charAt` at both ends and `indexOf`, no pattern: a remote address whose first character is `[`, whose
    last is `]`, and which has no other bracket loses that pair; any other bracket stays in the text and fails the literal check. Entries
    never have brackets removed, so a bracketed entry fails startup (IN-08).
  - Decimal form (IN-08, T022). T021's octet pattern, `25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9]`, already rejects leading zeros and values
    above 255 in a plain dotted quad. `getByName` does not apply this to the IPv4 part of an IPv6 literal: on JDK 21.0.11 and 25.0.4.1 it
    parses `::ffff:10.0.0.01` as `10.0.0.1`, accepts `::10.0.0.01`, and reads `010.0.0.1` as `10.0.0.1`; on JDK 21 it tries a DNS lookup for
    `10.0.0.256` (checked in `jshell`). So when an IPv6 literal contains `.`, the text after its last `:` must match the dotted-quad pattern.
    A prefix must match `0|[1-9][0-9]{0,2}` before `Integer.parseInt`, which would accept `08` and `+8`, and is then parsed once, into a local,
    and range-checked (T022 review).
  - `getByName` turns `::ffff:a.b.c.d` into an `Inet4Address`. So an IPv6-text entry that yields an `Inet4Address` is an IPv4-mapped entry and is
    rejected, and an IPv4-mapped remote address naturally matches IPv4 entries.
  - Host bits are checked by masking. A bare address gets `/32` or `/128`.
  - Matching compares only addresses of the same family.
- **Authentication filter (IN-04, IN-05, IN-07).** `ContextAuthenticationFilter` is in a nested configuration, `@ConditionalOnClass` on Spring
  Security's `SecurityContextHolder`.
  - It is registered one above `spring.security.filter.order`, read from the `Environment` as for the inbound filter, so `-99` by default and
    `6` at `5`: after the `springSecurityFilterChain` registration wherever it is configured (D38). A servlet filter after the security filter
    chain runs inside `FilterChainProxy`'s call to the original chain, so the `SecurityContext` is populated there. A property of
    `Integer.MAX_VALUE`, at which `securityOrder + 1` would wrap to `Integer.MIN_VALUE` and put the filter first, fails startup with
    `IllegalArgumentException`; the registration bean is `@Lazy(false)`, so it also fails under lazy initialization (T025, see Spring wiring).
  - An exception from the rest of the chain propagates unchanged, including an undeclared checked exception: the `catch (Exception e)` around
    `binding.call` rethrows through a private sneaky-rethrow helper, as in the inbound filter (D38).
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
  - nothing in the module is in an imports file, and its JAR ships no `AutoConfiguration.imports` and no `spring.factories` (T020a);
  - `-spring` is an `api` dependency of the module, because `InboundTrustVerifier` is in its public API; `spring-web` and
    `jakarta.servlet-api` stay `compileOnly` (D37, T020a).

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
  - T027: edge's `JwtClaimContributor` bean, edge's `/call-orders/as-caller` endpoint, which binds nothing itself and calls orders through
    `internal("orders")` (IT-2.1), and `TestJwts.withoutTenant()`.
- **The `-test` JAR's contents are checked in BuildSmokeIT (T020a).** A check by code source sees the module's resources only in the packaged
  JAR. In `:boundrail-context-test:test`, `TestInboundTrustVerifier`'s code source is `build/classes/java/main`, while resources are in
  `build/resources/main`, so a stray imports file would never be seen there. BuildSmokeIT runs against packaged JARs (BLD-06), finds the JAR
  through the code source, as `BLD-06 library classes load from packaged JARs` does for core, and opens it with `JarFile`. T020's unit test
  stays as it is.

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

The two user decisions from the T015 review are being written into spec.md (DIAG-02, POL-08) by the spec-creator: an invalid `violations.mode`
fails startup, also under lazy initialization, values match ignoring case and an empty value means `fail`; and in `report` mode an
application-set header of an unbound required key is sent. T015a is blocked until spec.md states both.

The user's decision from the T016 review, that the inbound filter's order is one below `spring.security.filter.order` (`-101` by default), is
being written into spec.md (IN-01) by the spec-creator. T017's configured-order test is blocked until that IN-01 text is committed.

The user's decision from the T017 review, that a `spring.security.filter.order` that is not an integer or is `Integer.MIN_VALUE` fails startup,
also under lazy initialization, is being written into spec.md (IN-01 and the lazy-initialization list) by the spec-creator. T018's IN-01
invalid-order test is blocked until that text is committed.

The IN-02 text that a single header line is one value, even with commas, decoded whole and never split (with its edge case), is in spec.md but
not yet committed. T019's comma test and T024's comma-containing `INVALID_VALUE` test are blocked until it is committed.

The user's decisions from the T021 review are committed in spec.md (IN-03, IN-08, the Configuration table and the edge cases, d62d28a):
one enclosing pair of square brackets is removed from the remote address; IPv4 octets and prefix lengths are plain decimal with no leading
zeros, also inside an IPv6 literal; and `inbound.trust` uses relaxed enum binding, blank means `none`, and any other value fails startup. Their
tests are planned in T022.

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
| T007 | `DIAG-03 the auto-configured reporter uses the configured log-interval` | T007's bean passes `properties.violations().logInterval()` in the same green step that makes the other log-interval tests pass | In `BoundrailContextAutoConfiguration`, the reporter bean built with `Duration.ofMinutes(1)` instead of the property |
| T007 | `DIAG-03 without Micrometer on the classpath the violation is logged` (the `doesNotHaveBean(ViolationReporter.Counter.class)` assertion) | T007's nested configuration has `@ConditionalOnClass(MeterRegistry.class)` from its first green step | The `@ConditionalOnClass` removed from the nested counter configuration |
| T010 | `OUT-03 with several DestinationClassifier beans the primary is used` | T009's customizers inject a single `DestinationClassifier`, which Spring resolves to the `@Primary` bean | In both customizers, the classifier taken from `ObjectProvider<DestinationClassifier>.orderedStream().findFirst().orElseThrow()`, with the test's non-primary bean registered first |
| T010 | `OUT-03 with several DestinationClassifier beans and no primary startup fails` | The same single-bean injection already fails with `NoUniqueBeanDefinitionException` | The same change, under which startup succeeds |
| T010 | `OUT-03 the last declaration applied to a builder wins` | `RestClient.Builder.defaultRequest` composes consumers with `andThen`, so the later `attribute` call overwrites the earlier | In `BoundrailClients.declare`, the attribute set with `attributes.putIfAbsent(DESTINATION_ATTRIBUTE, destination)`, so the first wins |
| T013 | IT-2.2 External call carries no context | After T010, the interceptor writes nothing to `EXTERNAL` | In `ContextClientInterceptor`, the classified destination replaced by `Destination(INTERNAL, empty)` |
| T013 | IT-2.3 Unknown means external | After T010, the interceptor writes nothing to `UNKNOWN` | The same change |
| T020a | IT-2.2 External call carries no context (the added assertion that partner received no `boundrail-test-peer`) | T020 applies `peer()` to edge's internal clients only | In `EdgeApp`, `peer()` applied to the `externalPartner` client |
| T020a | IT-2.3 Unknown means external (the added assertion that partner received no `boundrail-test-peer`) | The same | In `EdgeApp`, `peer()` applied to the `undeclaredPartner` client |
| T020a | `TEST-01 the boundrail-context-test JAR ships no AutoConfiguration.imports and no spring.factories` (BuildSmokeIT) | T020 added no such file to the module | A temporary `boundrail-context-test/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` listing `io.github.boundrail.context.spring.BoundrailContextAutoConfiguration`, a class in another package, which T020's package-name scan does not catch |
| T022 | `IN-08 prefix lengths are 0 to 32 for IPv4 and 0 to 128 for IPv6` | T021 accepts every prefix, `0` included | In `CidrInboundTrustVerifier`, the prefix range check starting at 1 |
| T022 | `IN-08 an invalid entry fails startup`, the eleven cases T021 already rejects (`10.0.0.0/`, `::ffff:10.0.0.1`, `fe80::1%eth0`, `localhost`, `orders.svc.test`, `10.0.0`, `[2001:db8::1]`, `[2001:db8::]/32`, `010.0.0.1`, `10.0.0.256`, `::ffff:10.0.0.01`) | T021 requires a `/` in every entry, checks the address with its literal check, and `Integer.parseInt` rejects an empty prefix | The `CidrInboundTrustVerifier` constructor skipping an entry whose parsing throws `IllegalArgumentException` |
| T022 | `IN-08 entries are parsed without DNS lookups`, rewritten in the T022 review to use a fresh `boundrail-dns-probe.invalid` name and `RecordingResolverProvider` | T022's textual literal check rejects the probe name before `getByName`, so no lookup is recorded | In `CidrInboundTrustVerifier`, entries passed straight to `InetAddress.getByName`, so the probe name is looked up and recorded |
| T022 | `IN-08 the remote address is parsed without DNS lookups` (T021's test), rewritten in the T022 review in the same way | T021's textual literal check rejects the probe name before `getByName`, so no lookup is recorded | In `CidrInboundTrustVerifier.verify`, the remote address passed straight to `InetAddress.getByName`, so the probe name is looked up and recorded |
| T022 | `IN-08 invalid entries are ignored when inbound.trust is not cidr` | T021 creates the CIDR verifier only with `inbound.trust=cidr` | In `boundrailInboundTrustVerifier`, a `CidrInboundTrustVerifier` built from `trusted-cidrs` before the switch on `trust` |
| T022 | `IN-03 an invalid inbound.trust fails startup with a binding error naming the property`, the case with an application verifier bean only. The cases without and with lazy initialization were red on T021's code, so they are not guards | With a verifier bean, binding `bogus` into the `Trust` enum already fails on T021's code | In `BoundrailContextProperties.Inbound`, the `trust` component a `String` |
| T022 | `IN-08 an over-long remote address is UNTRUSTED without costly parsing`, its input changed to `":".repeat(65535) + "%"` | T021's 45-character cap rejects it before any pattern runs | The cap removed and the IPv6 pattern replaced by the backtracking `[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*` (in `jshell` on JDK 21, about 0.4 s at 8,192 characters and 24 s at 65,536) |
| T022 | `IN-08 a bracketed IPv4 remote address is unbracketed` (e84bb96 follow-up) | T022's `unbracketed()` removes one enclosing pair whatever the text inside, so `[10.0.0.1]` is checked as `10.0.0.1` | In `CidrInboundTrustVerifier.unbracketed`, the brackets removed only when the text inside contains `:` |
| T022 | `IN-08 a hex-form IPv4-mapped remote address is matched as IPv4` (e84bb96 follow-up) | `getByName` turns `::ffff:a00:1` into an `Inet4Address`, as it does the dotted form, so it matches IPv4 entries | In `CidrInboundTrustVerifier.verify`, only the dotted form `::ffff:a.b.c.d` treated as mapped, e.g. `UNTRUSTED` for a remote address that parses to an `Inet4Address` but whose text isn't dotted |
| T022 | `IN-08 an invalid entry fails startup`, the hex-form IPv4-mapped cases `::ffff:a00:1` and `0:0:0:0:0:ffff:a00:1` (e84bb96 follow-up) | The mapped-entry check rejects any IPv6-text entry that `getByName` turns into an `Inet4Address`, whatever the form of its last 32 bits | In `CidrInboundTrustVerifier`, the mapped-entry check the textual `text.startsWith("::ffff:")` instead of `address instanceof Inet4Address` |
| T022 | `IN-08 IPv4-compatible and IPv4-translated entries are plain IPv6 entries` (e84bb96 follow-up) | `getByName` returns an `Inet6Address` for `::10.0.0.1` and `::ffff:0:10.0.0.1`, so they pass the mapped-entry check and match only IPv6 remote addresses | In `CidrInboundTrustVerifier`, any IPv6 entry with a dotted part rejected |
| T025 | `IN-04 an undeclared checked exception from the chain is rethrown unchanged` | T025's filter already rethrows the exception from `binding.call` through `sneakyThrow`, written before IN-04 named the behaviour (D38) | In `ContextAuthenticationFilter`, the `catch (Exception e)` around `binding.call` throwing `new ServletException(e)` instead |
| T026 | `IN-07 invalid(key) binds nothing` | `invalid` was red only at compile time, and a do-nothing `invalid` passes it | In `Contributions.invalid`, the key bound as if contributed |
| T027 | `IN-06 a missing claim leaves the key unbound and reports nothing` | `JwtClaimContributor` was red only at compile time, and a do-nothing `contribute` passes it | In `JwtClaimContributor.contribute`, the claim decoded and contributed without the absence check, so the codec throws `NullPointerException` |
| T027 | `IN-06 a non-JWT authentication contributes nothing` | The same | In `JwtClaimContributor.contribute`, the JWT-type check dropped, so the authentication is cast to `JwtAuthenticationToken` unconditionally and throws `ClassCastException` |
| T027 | IT-2.6 Forged tenant with no claim | No code binds `tenant` from an untrusted header, and alice's claim is absent | Edge's verifier bean replaced by one returning `TRUSTED_INTERNAL` for every request |
| T011 | `OUT-04 an application-set header for a key that propagates to EXTERNAL_CALL is sent unchanged on an external call` | T010's application-set check already runs on every boundary | In `ContextClientInterceptor`, the application-set check applied only on `INTERNAL_CALL`, so the context value overwrites the header |
| T011 | `OUT-04 an application-set header's context value is not encoded or checked` (the throwing `encode` and oversized cases) | T010 skips an application-set header before calling `encode`, and has no length check | In `ContextClientInterceptor`, the value encoded and length-checked before the application-set check |
| T011 | `WIRE-04 a value of exactly max-value-length is sent` | No length check existed before T011, so every value is sent | In `ContextClientInterceptor`, the length check written as `>=` instead of `>` |
| T012 | `WIRE-04 an outbound value both oversized and not a valid field value is reported once, as OVERSIZED_VALUE` | T011's length check already drops the value before any character check exists | In `ContextClientInterceptor`, the WIRE-08 check run before the WIRE-04 check |
| T012 | `OUT-04 an application-set header's context value is not encoded or checked` (the invalid field value case) | The application-set check already skips the key before it is encoded | In `ContextClientInterceptor`, the value encoded and checked (WIRE-04, then WIRE-08) before the application-set check |
| T012 | `WIRE-08 visible ASCII with inner spaces and tabs is written` | T011 has no field value check, so every value within the length limit is written | In `ContextClientInterceptor.isFieldValue`, SP (U+0020) and HTAB (U+0009) rejected |
| T012 | `WIRE-08 an empty value is written` | T011 has no field value check, so `""` is written | In `ContextClientInterceptor.isFieldValue`, the `isEmpty` case returning `false` |
| T015 | `POL-08 a required key that does not propagate to the call's boundary is not checked` | The test's key is unbound and asserted unchecked; before T015 nothing checks required keys | In `ContextClientInterceptor`, the propagation filter removed from the required-key check, so every unbound required key is checked |
| T015a | `DIAG-02 an invalid violations.mode fails startup` (without and with lazy initialization) | `Mode` is an enum, so binding `bogus` already fails, and the `@Lazy(false)` reporter binds the properties at startup | In `Violations`, an unconvertible `mode` defaulted to `FAIL` instead of failing (e.g. `mode` bound as a `String` and mapped to `FAIL` unless it equals `report`) |
| T015a | `DIAG-02 violations.mode is matched ignoring case and an empty value means fail` | Boot's lenient enum conversion ignores case, and converts `""` to `null`, which `@DefaultValue("fail")` replaces | Case: `mode` bound as a `String` and compared with `equals("report")`, so `REPORT` is `fail`. Empty value: `@DefaultValue("fail")` removed from `mode`, so `""` binds to `null` and the interceptor's `mode == FAIL` is false |
| T015a | `DIAG-02 in report mode an application-set header of an unbound required key is sent` | T015's check reads the context, not the headers, and step 3 writes nothing for an unbound key, so the application's header is untouched | In `ContextClientInterceptor`, in `report` mode, the wire header of each missing required key removed from the request |
| T017 | `IN-01 no inbound filter or verifier is registered outside a servlet web application` | T016's nested `InboundConfiguration` is already `@ConditionalOnWebApplication(type = SERVLET)`, so a plain `ApplicationContextRunner` creates neither bean | `@ConditionalOnWebApplication` removed from `InboundConfiguration` |
| T018 | `IN-02 a repeated Boundrail-Deadline-Ms on a trusted request is skipped, not reported` | Before T018 nothing reports a repeated header, and T017 already skips `DEADLINE` and `AUTHORITY` | On T018's finished code, the `DEADLINE`/`AUTHORITY` skip removed from `ContextInboundFilter`, so the repeated deadline header reaches the repetition check and gives `INVALID_VALUE` |
| T018 | `IN-01 a spring.security.filter.order that is not an integer or is Integer.MIN_VALUE fails startup`, the `abc` case without lazy initialization only | The registration bean reads the property from the `Environment` as an `Integer`, so `abc` already fails the conversion at startup. The other three cases are red before T018 | In `boundrailInboundFilter`, the property read as a `String` and the default `-100` used when it doesn't parse as an `int` |
| T019 | `IN-02 a single header line with commas is one value and is not split` | T018's repetition check counts header lines from `getHeaders`, and a single `Tenant-Id: a, b` line is one value, decoded whole by the string codec | In `ContextInboundFilter.bind`, each header value split on commas and more than one part treated as a repeat, so `tenant` is unbound with one `INVALID_VALUE` |
| T024 | `WIRE-04 an inbound value both oversized and undecodable is reported once, as OVERSIZED_VALUE, without calling the codec` | T023's length check already drops the value before decoding | In `ContextInboundFilter`, `decode` called before the length check |
| T024 | `WIRE-05 any other exception from decode propagates unchanged and no violation is reported` | Before T024 nothing catches an exception from `decode`, so every exception propagates | In `ContextInboundFilter`, the catch widened from `IllegalArgumentException` to `RuntimeException` |
| T028 | `IN-06 a claim value longer than max-value-length is bound` | No length check exists on the authentication path | In `ContextAuthenticationFilter`'s `Contributions.put`, `String` values longer than 256 (the default `wire.max-value-length`, hard-coded) dropped |
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
├── support/RecordingResolverProvider.java (InetAddressResolverProvider recording probe-name lookups)   # T022 review
boundrail-context-spring/src/test/resources/META-INF/services/java.net.spi.InetAddressResolverProvider  # T022 review
boundrail-context-test/build.gradle.kts                                                              # T020, T020a
boundrail-context-test/src/main/java/io/github/boundrail/context/test/TestInboundTrustVerifier.java   # T020
boundrail-context-test/src/test/java/io/github/boundrail/context/test/TestInboundTrustVerifierTest.java
integration-tests/build.gradle.kts                                                                   # T013, T014, T020a, T024
integration-tests/src/test/java/io/github/boundrail/context/it/BuildSmokeIT.java                     # T020a (TEST-01)
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
| POL-08 | T015 (one violation per unbound required key), T015a (`report` mode sends an application-set header of an unbound required key) |
| POL-10 | T005 |
| DEST-01 | T001, T029 (no HTTP dependency in core) |
| DEST-02 | T001, T002, T006a (trailing dot), T009 |
| DEST-03 | T010 (writing), T014 (stripping) |
| DEST-04 | T003, T009 (startup failure, also under lazy initialization) |
| WIRE-01 | T010 (outbound), T016 (inbound) |
| WIRE-02 | T010 (initiator out), T017 (initiator in; deadline and authority not bound) |
| WIRE-04 | T011 (outbound, UTF-16 code units, not on application-set headers), T012 (before WIRE-08), T023 (inbound), T024 (before decode), T028 (not on claims) |
| WIRE-05 | T010 (`encode` exceptions propagate), T024 (decode failures, including a single comma-containing value, other `decode` exceptions propagate), T028 (claims) |
| WIRE-06 | T010, T014 (outbound), T016, T018, T019 (inbound) |
| WIRE-07 | T014 (one violation per removed name with values, none for a name with no values, headers of keys propagating to `EXTERNAL_CALL` kept) |
| WIRE-08 | T012 (not on application-set headers) |
| IN-01 | T016, T017 (configured security order, undeclared checked exceptions, servlet only), T018 (invalid security order fails startup, also under lazy initialization) |
| IN-02 | T016, T017, T018, T019 (untrusted, empty header, a single comma-containing line), T024 (comma-containing value the codec rejects) |
| IN-03 | T016 (default, bean replaces), T021 (CIDR, `getRemoteAddr()`, non-literal remote address), T022 (relaxed, blank and invalid `inbound.trust`, also under lazy initialization and with a verifier bean) |
| IN-04 | T025 |
| IN-05 | T025 |
| IN-06 | T027 (valid, missing, non-JWT), T028 (non-string, undecodable, no length check, other codec exceptions) |
| IN-07 | T026 |
| IN-08 | T021 (address families, IPv4-mapped remote, non-literal, over-long and zone-ID remote addresses, no DNS), T022 (strict entries, square brackets, decimal form, startup failure also under lazy initialization, no DNS; bracketed IPv4 remote addresses, hex-form IPv4-mapped remote addresses and entries, IPv4-compatible and IPv4-translated entries) |
| OUT-01 | T008, T009 (at most once per client) |
| OUT-02 | T009 |
| OUT-03 | T009, T010 (several classifier beans, none primary also under lazy initialization, last declaration wins) |
| OUT-04 | T010, T011 (application-set headers on every boundary, a name with no values is unset, `""` is set, no `encode` or length check), T012 (no field-value check), T014 |
| DIAG-01 | T004 |
| DIAG-02 | T004 (exception), T015 (report, then throw; several keys in name order, exception carries the first), T015a (invalid mode fails startup, also under lazy initialization; case-insensitive values; empty means `fail`; `report` mode sends an application-set header); report-only kinds under the default `fail` mode in T011, T012, T014, T018, T019, T023, T024, T026 |
| DIAG-03 | T007, T010 (startup failure under lazy initialization, also with an application classifier) |
| DIAG-08 | T018, T019, T023, T024, T026, T028 |
| DIAG-09 | T007 (log entry content), T011, T012, T023, T024, T028 (`Violation` records); IT-2.8 (log entries, counter tags) |
| TEST-01 | T020, T020a (dependency scopes in the POM and module metadata; no auto-configuration file in the JAR) |
| IT-2.1 | T027 |
| IT-2.2, IT-2.3 | T013 (guard), T020a (no test peer header reaches partner, guard) |
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
