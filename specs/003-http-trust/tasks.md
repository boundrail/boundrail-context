---
description: "Task list for 003-http-trust"
---

# Tasks: HTTP Boundaries and Trust

**Input**: Design documents from `/specs/003-http-trust/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [contracts/http-api.md](contracts/http-api.md); 002-core-runtime merged (47ea70b)

**Tests**: Required (constitution principle VII). Each task is one red-green cycle: its failing test, with the behaviour it pins down, and the
minimum code that makes it pass, committed and reviewed together. Every task leaves `./gradlew check` green. Every test's display name starts
with the requirement or scenario ID it covers.

- No TDD exceptions are proposed ([plan.md](plan.md#tdd-exceptions)).
- Forty-four guard tests pass before their task: IT-2.2, IT-2.3 and IT-2.6, T020a's added IT-2.2 and IT-2.3 assertions, one test each in
  T002, T005, T015, T017, T019 and T020a, two unit tests each in T001, T003, T006a, T007, T018 (one of them a single parametrized case), T024
  and T028, three unit tests each in T010, T011 and T015a, four unit tests in T012, and six unit tests in T022 (one of them the cases of a
  parametrized test that T021's code already rejects).
  Their red is shown against a temporary code change that is reverted ([plan.md](plan.md#guard-tests)). The user is asked to accept them.
- A test that is green before its task is accepted as a guard when its red is shown against a named temporary change to the finished code,
  which is then reverted; the leader reports it in the task report, the planner adds it to plan.md's Guard Tests table, and the reviewer
  checks it.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: The user story the task belongs to (US1: outbound, US2: inbound)

Path abbreviations:

| Short | Path |
|---|---|
| `core/` | `boundrail-context-core/src/main/java/io/github/boundrail/context/` |
| `coretest/` | `boundrail-context-core/src/test/java/io/github/boundrail/context/` |
| `spring/` | `boundrail-context-spring/src/main/java/io/github/boundrail/context/spring/` |
| `springtest/` | `boundrail-context-spring/src/test/java/io/github/boundrail/context/spring/` |
| `testmod/` | `boundrail-context-test/src/main/java/io/github/boundrail/context/test/` |
| `testmodtest/` | `boundrail-context-test/src/test/java/io/github/boundrail/context/test/` |
| `topology/` | `integration-tests/src/test/java/io/github/boundrail/context/it/topology/` |
| `TrustBoundaryIT` | `integration-tests/src/test/java/io/github/boundrail/context/it/TrustBoundaryIT.java` |

Verify commands:

- **Unit**: `./gradlew :<project>:test --tests '<TestClass>'` (with `--tests` repeated for each class) for red and green, then `./gradlew check`
  before commit.
- **IT**: the scenario run, then `./gradlew check` before commit. `--tests` applies only to the task just before it, and `--continue` reports a
  red run in both tasks:

  ```bash
  ./gradlew --continue :integration-tests:integrationTestJdk21 --tests '*TrustBoundaryIT' \
            :integration-tests:integrationTestJdk25 --tests '*TrustBoundaryIT'
  ```

Enabling a scenario means:

- remove `@Disabled`;
- keep its `@DisplayName` (already `IT-2.n <title>`, as spec.md bolds it) and the comment with the spec text;
- write the body.

Each scenario asserts violation counts as a delta around its own request (`Running.violations`), never a total, because the topology is shared.

## Phase 1: Setup

None. 001-foundation built the projects, the integration test tasks, the topology skeleton and the disabled TrustBoundaryIT. Each dependency
this feature needs is added in the green step of the first task whose test needs it ([plan.md](plan.md#build)).

---

## Phase 2: Foundational — core types (blocks every story)

- [x] T001 [P] Destinations and classification order.
  - Red, in `coretest/HostPatternClassifierTest.java`, all with exact host names:
    - `DEST-02 a declared destination wins over host patterns`: a declared `INTERNAL("orders")` for a host in the external list, and a declared
      `EXTERNAL` for a host in the internal list, come back unchanged, service name included;
    - `DEST-02 internal patterns are checked before external patterns`: a host in both lists is `INTERNAL`;
    - `DEST-02 an external pattern match is EXTERNAL`;
    - `DEST-02 a host matching no pattern is UNKNOWN`, also for a URI without a host;
    - `DEST-02 a host-pattern match yields no service name`;
    - `DEST-02 a URI whose host does not parse is UNKNOWN`: `http://orders_svc:8080/` is `UNKNOWN`. This is a guard: show it red with
      `classify` temporarily falling back to the URI's raw authority host when `getHost()` is `null`, then revert
      ([plan.md](plan.md#guard-tests));
    - `DEST-02 of copies its lists`: changing either list after `of` returns doesn't change a classification. This is a guard: show it red
      with `of` temporarily keeping the caller's lists instead of copying them, still rejecting `null` elements, then revert
      ([plan.md](plan.md#guard-tests));
    - `DEST-01 destination types depend on no HTTP library`: by reflection, the four classes' fields, constructors and method signatures name
      only `java.*` and core types, and none from `java.net.http` or `com.sun.net.httpserver`;
    - `CORE-13 destination types reject null`: `Destination` with a `null` kind or service, `OutboundRequest` with a `null` URI or declaration,
      `HostPatternClassifier.of` with a `null` list or list element, and `classify(null)` each throw `NullPointerException`.
  - Green: `core/Destination.java` (record with `Kind`), `core/OutboundRequest.java`, `core/DestinationClassifier.java`, and
    `core/HostPatternClassifier.java` with `of` (copies both lists) and `classify` in the DEST-02 order, exact names only.
  - Completes: DEST-01; DEST-02 (order); CORE-13 (DEST types).
  - Verify: Unit `HostPatternClassifierTest` in `:boundrail-context-core`.
- [x] T002 Wildcard and case-insensitive host patterns.
  - Red, in `HostPatternClassifierTest`:
    - `DEST-02 host patterns compare case-insensitively`: `Orders.SVC.test` against `orders.svc.test`, and a pattern written in upper case;
    - `DEST-02 a leading *. wildcard matches one or more labels`: `*.svc.test` matches `a.svc.test` and `a.b.svc.test`;
    - `DEST-02 a wildcard does not match the bare suffix`: `*.svc.test` matches neither `svc.test` nor `xsvc.test`. This is a guard: show it
      red with `classify` temporarily matching a wildcard with `host.endsWith(pattern.substring(2))`, with no leading dot, then revert
      ([plan.md](plan.md#guard-tests)).
  - Green: wildcard and case-insensitive matching with `Locale.ROOT` in `HostPatternClassifier`.
  - Completes: DEST-02.
  - Verify: Unit `HostPatternClassifierTest`.
- [x] T003 Host pattern validation.
  - Red, in `HostPatternClassifierTest`:
    - `DEST-04 invalid host patterns are rejected`: `HostPatternClassifier.of` throws `IllegalArgumentException` for each of `*`, `*.`,
      `a.*.b`, `foo*`, the empty string, `orders:8080`, `orders.svc.`, `a..b`, `.a`, `-a.b`, `a-.b`, a 64-character label, a 254-character
      name, `10.0.0.1`, `*.10.0.0.1`, `::1` and `[::1]`, in either list;
    - `DEST-04 valid host patterns are accepted`: `orders`, `orders.svc.test`, `*.svc.test`, `ORDERS.svc.test`, `a-b.c`, `123.example`, a
      63-character label, a 253-character name, and `*.` followed by a 253-character name. This is a guard: show it red with `of`
      temporarily counting the 253-character limit over the whole pattern including `*.`, then revert ([plan.md](plan.md#guard-tests));
    - `CORE-13 a null pattern list is rejected before pattern validation`: `of(List.of("*"), null)` throws `NullPointerException`. This is a
      guard: show it red with `of` temporarily validating the internal list before copying the external list, then revert
      ([plan.md](plan.md#guard-tests)).
  - Rewrite T001's guard `DEST-02 a URI whose host does not parse is UNKNOWN`, whose pattern `orders_svc` is invalid under DEST-04: it now
    builds the classifier with the pattern `*.svc.test` in both lists and expects `http://orders_x.svc.test:8080/` to be `UNKNOWN` with no
    service name. `getHost()` is `null` for that URI, and a raw-authority fallback would match it. Show it red again with `classify`
    temporarily falling back to the URI's raw authority host when `getHost()` is `null`, then revert ([plan.md](plan.md#guard-tests)).
  - Green: per-pattern validation in `of` ([plan.md](plan.md#core)).
  - Completes: DEST-04; CORE-13 (precedence).
  - Verify: Unit `HostPatternClassifierTest`.
- [x] T004 [P] Violation and ContextViolationException.
  - Red, in `coretest/ViolationTest.java`:
    - `DIAG-01 Violation.Kind has exactly the six kinds of the contract`: `MISSING_REQUIRED`, `UNTRUSTED_HEADER`, `INVALID_VALUE`,
      `OVERSIZED_VALUE`, `FORBIDDEN_HEADER` and `MISSING_AUDIENCE`, in that order, and no other;
    - `DIAG-01 a violation exposes its evidence`: the seven accessors return what was given;
    - `DIAG-02 ContextViolationException carries its violation`: `violation()` returns the constructor's argument; the exception is a
      `RuntimeException`; its message names the kind, key and boundary;
    - `CORE-13 violation types reject null`: each `null` component of `Violation`, and `new ContextViolationException(null)`, throws
      `NullPointerException`.
  - Green: `core/Violation.java` (record with `Kind`, fields as the contract) and `core/ContextViolationException.java`.
  - Completes: DIAG-01; DIAG-02 (exception type); CORE-13 (violation types).
  - Verify: Unit `ViolationTest`.
- [x] T005 [P] Policy keys.
  - Red, in `coretest/ContextPolicyTest.java`:
    - `POL-10 keys() returns the registered and built-in keys`: a built policy reports exactly its registered keys plus `DEADLINE`, `AUTHORITY`
      and `INITIATOR`;
    - `POL-10 the default policy reports the built-in keys`: `BuiltInKeys.defaultPolicy()` reports the three built-in keys;
    - `POL-10 a merged policy reports the union of its keys`;
    - `POL-10 keys() is unmodifiable`. This is a guard: a `Set.of()` stub can't be changed either. Its red is shown with `keys()` temporarily
      returning `new HashSet<>(rules.keySet())`, then reverted ([plan.md](plan.md#guard-tests)).
  - Green: `keys()` in `core/ContextPolicy.java` over the rules map.
  - Completes: POL-10.
  - Verify: Unit `ContextPolicyTest`.

**Checkpoint**: core has the destination model, pattern validation, `Violation`, `ContextViolationException` and `keys()`;
`boundrail-context-core` still has no dependencies.

---

## Phase 3: Foundational — Spring auto-configuration (blocks every story)

- [x] T006 Auto-configuration skeleton and effective policy.
  - Red:
    - `springtest/AutoConfigurationImportsTest.java`: `POL-07 the auto-configuration is registered for Spring Boot`
      (`ImportCandidates.load(AutoConfiguration.class, classLoader)` contains `BoundrailContextAutoConfiguration`);
    - `springtest/EffectivePolicyTest.java` (`ApplicationContextRunner`):
      - `POL-07 with no ContextPolicy bean the effective policy is the built-in policy`: an application key is unregistered, and `INITIATOR`
        reports `Boundrail-Initiator`;
      - `POL-07 every ContextPolicy bean is merged with the built-in policy`: with two beans with different keys, both keys' rules are present;
      - `POL-07 conflicting ContextPolicy beans fail startup`: `IllegalStateException` is the root cause of the startup failure.
  - Green:
    - `boundrail-context-spring/build.gradle.kts`: `testImplementation(libs.spring.boot.starter.test)` (the catalog entry exists);
    - `spring/BoundrailContextAutoConfiguration.java` (`@AutoConfiguration`);
    - package-private `spring/EffectivePolicy.java`;
    - `boundrail-context-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

    `TopologySmokeIT` must stay green now that every topology service loads the auto-configuration.
  - Completes: POL-07.
  - Verify: Unit `AutoConfigurationImportsTest` and `EffectivePolicyTest` in `:boundrail-context-spring`; `./gradlew check`.
- [x] T006a Effective policy scope and eager startup.
  - Red:
    - in `springtest/EffectivePolicyTest.java`:
      - `POL-07 conflicting ContextPolicy beans fail startup with lazy initialization`: the two conflicting beans of T006's test, with
        `spring.main.lazy-initialization=true` and `LazyInitializationBeanFactoryPostProcessor` added through `withInitializer`, as
        `SpringApplication` does for that property (`ApplicationContextRunner` does not run `SpringApplication`). `IllegalStateException` is
        the root cause of the startup failure. Red against T006's code, whose bean is then lazy and never created;
      - `POL-07 ContextPolicy beans in a parent context are merged`: a parent context with a `ContextPolicy` bean registering a key, set
        with `withParent`; that key is in the child's effective policy's `keys()`, and so are the built-in keys. This is a guard: T006's
        `ObjectProvider.orderedStream()` searches ancestor contexts, so the current code already passes it. Show it red with
        `boundrailEffectivePolicy` temporarily collecting the beans with the local-only
        `ListableBeanFactory.getBeansOfType(ContextPolicy.class)`, then revert ([plan.md](plan.md#guard-tests));
    - in `coretest/HostPatternClassifierTest.java`:
      - `DEST-02 a host with a trailing dot is UNKNOWN`: with `orders.svc.test` and `*.svc.test` in both lists, `http://orders.svc.test./` is
        `UNKNOWN` with no service name. This is a guard: the current code already returns `UNKNOWN`. Show it red with `classify` temporarily
        stripping one trailing dot from the host, then revert ([plan.md](plan.md#guard-tests)).
  - Green: `@Lazy(false)` on the `boundrailEffectivePolicy` bean method in `spring/BoundrailContextAutoConfiguration.java`
    ([plan.md](plan.md#spring-wiring)).
  - Completes: POL-07 (parent context merged; eager under lazy initialization); DEST-02 (trailing-dot edge case).
  - Verify: Unit `EffectivePolicyTest` in `:boundrail-context-spring` and `HostPatternClassifierTest` in `:boundrail-context-core`;
    `./gradlew check`.
- [x] T007 Violation reporting.
  - Red, in `springtest/ViolationReporterTest.java`, with `springtest/support/RecordingLog.java` recording entries:
    - `DIAG-03 a violation is logged at WARN`: one WARN entry naming the kind, key, boundary, destination and thread;
    - `DIAG-09 the log entry identifies the value by key name, wire name and boundary`: `tenant` with `Tenant-Id` and `INTERNAL_CALL`; a key
      with no wire name is logged without one;
    - `DIAG-03 log entries are rate-limited per kind, key and boundary` (with a test `Clock`):
      - a second identical violation inside `log-interval` is not logged;
      - one with another kind, key or boundary is;
      - after the interval has passed, it is logged again;
    - `DIAG-03 log-interval defaults to one minute and is configurable`;
    - `DIAG-03 the auto-configured reporter uses the configured log-interval` (`ApplicationContextRunner`): with
      `boundrail.context.violations.log-interval=0s`, two identical violations reported through the context's `ViolationReporter` give two
      log entries (`0s` logs every occurrence). This is a guard: show it red with the bean temporarily ignoring the property and using
      `Duration.ofMinutes(1)`, then revert ([plan.md](plan.md#guard-tests));
    - `DIAG-03 a negative log-interval fails startup`: `log-interval=-1s` fails the context;
    - `DIAG-03 every occurrence is counted even when the log entry is rate-limited`: three identical violations count 3 in
      `boundrail.context.violations` of the `MeterRegistry` bean;
    - `DIAG-03 counter tags are kind, key and boundary in lower case`: `kind=forbidden_header`, `key=tenant`, `boundary=external_call`;
    - `DIAG-03 with a primary MeterRegistry bean among several the counter is in the primary`: nothing is in the other bean;
    - `DIAG-03 with several MeterRegistry beans and no primary the violation is logged and not counted`: startup succeeds;
    - `DIAG-03 without a MeterRegistry bean the violation is logged and the global registry is untouched`: a `SimpleMeterRegistry` added to
      `Metrics.globalRegistry` for the test, and removed after, has no `boundrail.context.violations` meter;
    - `DIAG-03 without Micrometer on the classpath the violation is logged`: `FilteredClassLoader(MeterRegistry.class)`; the context has no
      `ViolationReporter.Counter` bean (`doesNotHaveBean`). This is a guard: show it red with the `@ConditionalOnClass` temporarily removed
      from the nested configuration, then revert ([plan.md](plan.md#guard-tests)).
  - Green:
    - package-private `spring/ViolationReporter.java`: `Log` and `Clock` through a package-private constructor; wire names through
      `EffectivePolicy` and `keys()`;
    - the counter function in a nested `@ConditionalOnClass(MeterRegistry.class)` configuration using `ObjectProvider.getIfUnique()`;
    - `spring/BoundrailContextProperties.java` with `violations.log-interval`; the `Violations` compact constructor rejects a negative
      `Duration` with `IllegalArgumentException`, and `0s` is accepted;
    - `micrometer-core` as `testImplementation`.
  - Completes: DIAG-03; DIAG-09 (log entry content).
  - Verify: Unit `ViolationReporterTest`.

**Checkpoint**: every topology service loads the auto-configuration; the effective policy and the reporter exist.

---

## Phase 4: User Story 1 - Internal calls carry context, external calls carry none (Priority: P1) 🎯 MVP

**Goal**: The outbound interceptor classifies every call, writes declared context to internal services only, strips forbidden headers, and
enforces required keys, the length limit and valid field values.

**Independent Test**: IT-2.2, IT-2.3, IT-2.4 and IT-2.9 in the IT command. IT-2.1 also needs US2's inbound path and is enabled in T028.

- [x] T008 [US1] Interceptor on every auto-configured client builder.
  - Red, in `springtest/ClientRegistrationTest.java`:
    - `OUT-01 the interceptor is added to the auto-configured RestClient.Builder`: the context's builder, read through
      `requestInterceptors(list -> …)`, contains the library's interceptor;
    - `OUT-01 the interceptor is added to the auto-configured RestTemplateBuilder`: a `RestTemplate` built from it lists the interceptor;
    - `OUT-01 the interceptor executes the request exactly once and returns its response`: a real call through the builder to
      `springtest/support/RecordingServer.java`, a JDK `HttpServer` recording requests, which receives one request; the response body is
      returned;
    - `OUT-01 without spring-boot-restclient no client customizer is registered` (`FilteredClassLoader`).
  - Green:
    - package-private `spring/ContextClientInterceptor.java`, which calls `execution.execute` once and nothing else yet;
    - a `RestClientCustomizer` bean and a `RestTemplateCustomizer` bean in a nested `@ConditionalOnClass` configuration;
    - `compileOnly` and `testImplementation` for `spring-web` and `spring-boot-restclient`, with catalog entries.
  - Completes: OUT-01.
  - Verify: Unit `ClientRegistrationTest`.
- [x] T009 [US1] Classification and per-client declarations.
  - Red, in `springtest/BoundrailClientsTest.java`, through a recording `DestinationClassifier` bean that captures each `OutboundRequest`:
    - `OUT-03 internal(service) declares Destination(INTERNAL, service) for every request of the client`: two requests;
    - `OUT-03 external() declares Destination(EXTERNAL, empty)`: two requests;
    - `OUT-02 the interceptor classifies the request URI and the declaration`: an undeclared client's captured request has the URI and an empty
      declaration; likewise for `RestTemplate`;
    - `OUT-03 the default classifier is a HostPatternClassifier from destinations.internal-hosts and external-hosts`: with the properties set,
      an undeclared request to an internal host is `INTERNAL`, to an external host `EXTERNAL`, to any other host `UNKNOWN`; both lists default
      to empty;
    - `OUT-03 a DestinationClassifier bean replaces the default`;
    - `DEST-04 an invalid destinations pattern fails startup`: `destinations.internal-hosts=*`; `IllegalArgumentException` is the root cause;
    - `DEST-04 an invalid destinations pattern fails startup with lazy initialization`: `destinations.internal-hosts=*` with
      `spring.main.lazy-initialization=true` and `LazyInitializationBeanFactoryPostProcessor` added through `withInitializer`, as in T006a;
      `IllegalArgumentException` is the root cause;
    - `CORE-13 BoundrailClients.internal(null) throws NullPointerException`.
  - Red, in `springtest/ClientRegistrationTest.java` (both red against T008's code):
    - `OUT-01 customizing a RestTemplate twice adds the interceptor once`: the context's `RestTemplateCustomizer` bean run twice on one
      `RestTemplate` leaves exactly one `ContextClientInterceptor` in `getInterceptors()`;
    - `OUT-01 customizing a RestClient.Builder twice adds the interceptor once`: the context's `RestClientCustomizer` bean run twice on one
      `RestClient.Builder` leaves exactly one `ContextClientInterceptor`, read through `requestInterceptors(list -> …)`.
  - Tighten T008's two registration tests in `ClientRegistrationTest` (`… added to the auto-configured RestClient.Builder` and `… added to
    the auto-configured RestTemplateBuilder`): assert exactly one interceptor, `filteredOn(ContextClientInterceptor.class::isInstance)
    .hasSize(1)`, instead of "contains". They stay green.
  - Fix `springtest/support/RecordingServer.java`, which T009's requests are the first after T008 to use: `uri()` builds the URI with the
    multi-argument constructor, `new URI("http", null, host, port, null, null, null)`, which brackets an IPv6 host such as `::1`. Today it
    concatenates the host string, so the URI is invalid when the loopback address is IPv6.
  - Green:
    - `spring/BoundrailClients.java` (request attribute through `defaultRequest`);
    - the `DestinationClassifier` bean (`@ConditionalOnMissingBean`, `@Lazy(false)`, so it is built at startup under lazy initialization)
      and the `destinations.*` properties;
    - classification in `ContextClientInterceptor`;
    - each customizer adds the interceptor only when none is present: `noneMatch(ContextClientInterceptor.class::isInstance)` over the
      template's `getInterceptors()` or the builder's interceptor list.
  - Completes: OUT-01 (at most once per client); OUT-02; OUT-03; DEST-02 (Spring wiring); DEST-04 (startup, also under lazy initialization);
    CORE-13 (`BoundrailClients`).
  - Verify: Unit `BoundrailClientsTest`, `ClientRegistrationTest`.
- [x] T010 [US1] Writing context headers.
  - Red, in `springtest/ContextClientInterceptorTest.java` (real calls to `RecordingServer`):
    - `OUT-04 an internal call carries the bound keys that propagate to INTERNAL_CALL` and `WIRE-01 a key is sent in the header named by its
      wire name, encoded by its codec`: `tenant=acme` as `Tenant-Id: acme`, `priority=5` as `Priority: 5`;
    - `WIRE-02 an internal call carries the initiator's value in Boundrail-Initiator`;
    - `OUT-04 an internal call writes no Authorization and no Boundrail-Deadline-Ms`: with `AUTHORITY` and `DEADLINE` bound, the same request
      carries `Tenant-Id`;
    - `OUT-04 unbound keys, keys with only a default, unregistered keys and keys not propagating to INTERNAL_CALL are not sent`: with
      `Tenant-Id` sent in the same request;
    - `OUT-04 an external call carries only keys that propagate to EXTERNAL_CALL`: an application key that does is sent; with the topology's
      policy, none is;
    - `DEST-03 an UNKNOWN destination is written like EXTERNAL`;
    - `OUT-04 a wire header set by application code on an internal call is left unchanged`;
    - `WIRE-06 the application-set check ignores the header name's case`: `tenant-id: mine` stays the only value;
    - `OUT-04 an exception thrown by a codec's encode propagates unchanged and no violation is reported`, parametrized over an application
      codec whose `encode` throws `IllegalStateException` and one whose `encode` throws `IllegalArgumentException` (WIRE-05: only `decode`
      has a rejection). On an internal call, the caller of the `RestClient` receives the same exception instance, `RecordingServer` received
      nothing, and the reporter recorded nothing.
  - Red, in `springtest/BoundrailClientsTest.java`:
    - `OUT-03 with several DestinationClassifier beans the primary is used`: two recording classifier beans, the non-primary one registered
      first and the second `@Primary`; an undeclared request is captured by the primary only. This is a guard: T009's customizers inject a
      single `DestinationClassifier`, which Spring resolves to the primary. Show it red with both customizers temporarily taking
      `ObjectProvider<DestinationClassifier>` and using `orderedStream().findFirst().orElseThrow()`, then revert
      ([plan.md](plan.md#guard-tests));
    - `OUT-03 with several DestinationClassifier beans and no primary startup fails`: two classifier beans, neither primary;
      `NoUniqueBeanDefinitionException` is the root cause of the startup failure. This is a guard: the customizers' single
      `DestinationClassifier` parameter already fails. Show it red with the same temporary change, then revert ([plan.md](plan.md#guard-tests));
    - `OUT-03 with several DestinationClassifier beans and no primary startup fails with lazy initialization`: the same two beans, with
      `spring.main.lazy-initialization=true` and `LazyInitializationBeanFactoryPostProcessor` added through `withInitializer`, as in T006a;
      `NoUniqueBeanDefinitionException` is the root cause. Red against T009's code, whose customizer beans are then lazy and never created;
    - `OUT-03 the last declaration applied to a builder wins`: a builder customized with `internal("orders")` then `external()` declares
      `Destination(EXTERNAL, empty)`; one customized with `external()` then `internal("orders")` declares `Destination(INTERNAL, "orders")`.
      This is a guard: `RestClient.Builder.defaultRequest` composes its consumers with `andThen`, so the later attribute overwrites the
      earlier. Show it red with `BoundrailClients.declare` temporarily setting the attribute with
      `spec.attributes(attributes -> attributes.putIfAbsent(DESTINATION_ATTRIBUTE, destination))`, so the first wins, then revert
      ([plan.md](plan.md#guard-tests)).
  - Red, in `springtest/ViolationReporterTest.java`:
    - `DIAG-03 a negative log-interval fails startup with lazy initialization`: `violations.log-interval=-1s` with
      `spring.main.lazy-initialization=true` and `LazyInitializationBeanFactoryPostProcessor` added through `withInitializer`, as in T006a;
      `IllegalArgumentException` is the root cause. Two cases: without a `DestinationClassifier` bean, and with an application
      `DestinationClassifier` bean. The second is red against T009's code: the application bean replaces the `@Lazy(false)` default
      classifier, and no other eager bean binds `BoundrailContextProperties`, so startup succeeds.
  - Green:
    - header writing in `ContextClientInterceptor` from `policy.filter(Context.capture(), boundary)`, with `INITIATOR` encoded as `value()`,
      and `AUTHORITY` and `DEADLINE` skipped. Nothing catches an exception from `encode`;
    - `@Lazy(false)` on both customizer bean methods in `ClientConfiguration` in `spring/BoundrailContextAutoConfiguration.java`;
    - `@Lazy(false)` on the `boundrailViolationReporter` bean method in the same file.

    The three guards need no code.
  - Completes: OUT-04 (writing, `encode` exceptions); WIRE-01 (outbound); WIRE-02 (outbound initiator); WIRE-05 (outbound); WIRE-06
    (outbound); DEST-03 (writing); OUT-03 (several classifier beans, none primary also under lazy initialization, last declaration wins);
    DIAG-03 (startup failure under lazy initialization, also with an application classifier).
  - Verify: Unit `ContextClientInterceptorTest`, `BoundrailClientsTest`, `ViolationReporterTest`.
- [x] T011 [US1] Outbound length limit.
  - Red, in `ContextClientInterceptorTest`:
    - `WIRE-04 an encoded value longer than max-value-length is not sent and is reported as OVERSIZED_VALUE`: for a 257-character tenant, no
      `Tenant-Id` is sent and one `OVERSIZED_VALUE` is reported for key `tenant`, boundary `INTERNAL_CALL`, with the classified destination. The
      request is sent with the other keys, in the default `fail` mode (DIAG-02). The violation and its log entry do not contain the value
      (DIAG-09);
    - `WIRE-04 a value of exactly max-value-length is sent` (256). This is a guard: no length check existed before this task, so every
      value is sent. Show it red with the length check temporarily written as `>=` instead of `>` in `ContextClientInterceptor`, then revert
      ([plan.md](plan.md#guard-tests));
    - `WIRE-04 max-value-length is configurable and defaults to 256`: a limit of 10;
    - `WIRE-04 the length is counted in UTF-16 code units`: with a limit of 10, a tenant of six U+1F600 characters (6 code points, 12 code
      units) is not sent and gives one `OVERSIZED_VALUE`;
    - `OUT-04 a header name with no values counts as unset and the context value is written`: with `tenant=acme` and `priority=5` bound on an
      internal call, application code puts `Tenant-Id` with no values (`headers.put("Tenant-Id", new ArrayList<>())`) and sets `Priority`
      to `""`. The server receives `Tenant-Id: acme` and `Priority` with the single value `""` (`""` is a value, so `Priority` is set and left
      unchanged). Red against T010, whose `containsHeader` check skips the name with no values, so no `Tenant-Id` is sent;
    - `OUT-04 an application-set header for a key that propagates to EXTERNAL_CALL is sent unchanged on an external call`: with `channel=web`
      bound (`CHANNEL` propagates to `EXTERNAL_CALL`) and application code setting `Channel: mine` on an `external()` call, the server receives
      only `Channel: mine`. This is a guard: T010's application-set check already runs on every boundary. Show it red with the
      application-set check temporarily applied only on `INTERNAL_CALL`, so the context value overwrites the header, then revert
      ([plan.md](plan.md#guard-tests));
    - `OUT-04 an application-set header's context value is not encoded or checked`, parametrized over two cases on an internal call, each
      with application code setting the key's header to `mine`: a key whose codec's `encode` throws `IllegalStateException`, and a
      257-character tenant. In each case no exception reaches the caller, the server receives only `mine` under the header, and no violation
      is reported (no `OVERSIZED_VALUE`). This is a guard: T010 skips an application-set header before calling `encode`, and has no length
      check. Show it red with the value temporarily encoded and length-checked before the application-set check (the first case throws, the
      second reports `OVERSIZED_VALUE`), then revert ([plan.md](plan.md#guard-tests)).
  - Green:
    - the application-set check counts a header as set only when it has at least one value (`!request.getHeaders().getOrEmpty(wireName)
      .isEmpty()`) instead of `containsHeader`, still on every boundary and still before `encode`;
    - the length check (`String.length()`) after encoding and before setting each header, `wire.max-value-length` in
      `BoundrailContextProperties`, and reporting through `ViolationReporter`.
  - Completes: WIRE-04 (outbound, only on values the interceptor writes); OUT-04 (application-set header on every boundary, a name with no
    values is unset, `""` is set, an application-set header's context value is neither encoded nor length-checked); DIAG-02 (report-only
    kind).
  - Verify: Unit `ContextClientInterceptorTest`.
- [x] T012 [US1] Outbound field values.
  - Red, in `ContextClientInterceptorTest`:
    - `WIRE-08 a value that is not a valid field value is not written and is reported as INVALID_VALUE`, parametrized over CR, LF, CRLF, NUL,
      DEL (U+007F), `é` (U+00E9), U+0080, an emoji, a leading space and a trailing tab. Each request is sent with the other keys, and one
      `INVALID_VALUE` is reported for key `tenant` with the classified destination;
    - `WIRE-08 visible ASCII with inner spaces and tabs is written`: `a b\tc`. This is a guard: T011 has no field value check. Show it red
      with `isFieldValue` temporarily rejecting SP and HTAB, then revert ([plan.md](plan.md#guard-tests));
    - `WIRE-08 an empty value is written`: `Tenant-Id` with an empty value. This is a guard for the same reason. Show it red with
      `isFieldValue`'s `isEmpty` case temporarily returning `false`, then revert ([plan.md](plan.md#guard-tests));
    - `WIRE-08 applies to Boundrail-Initiator`: an initiator `a\nb` is not written and is reported;
    - `DIAG-09 an invalid outbound value is reported without the value`: neither the violation nor the log entry contains it;
    - `WIRE-04 an outbound value both oversized and not a valid field value is reported once, as OVERSIZED_VALUE`. This is a guard: show it red
      with the WIRE-08 check temporarily moved before the WIRE-04 check, then revert ([plan.md](plan.md#guard-tests));
    - add a third case to T011's `OUT-04 an application-set header's context value is not encoded or checked`: a tenant `a\nb` with
      application code setting `Tenant-Id: mine`; the server receives only `mine` and no `INVALID_VALUE` is reported. This is a guard: the
      application-set check already skips the key before encoding. Show it red with the value temporarily encoded and checked (WIRE-04,
      then WIRE-08) before the application-set check, so `INVALID_VALUE` is reported, then revert ([plan.md](plan.md#guard-tests));
    - `WIRE-04 a non-ASCII value within max-value-length in UTF-16 code units but not in UTF-8 bytes is reported as INVALID_VALUE, not
      OVERSIZED_VALUE`: with a limit of 10, a tenant of six `é` (U+00E9; 6 UTF-16 code units, 12 UTF-8 bytes) is not sent, and exactly one
      violation is reported, `INVALID_VALUE` for key `tenant` (WIRE-08), with no `OVERSIZED_VALUE`. A length counted in UTF-8 bytes would
      report `OVERSIZED_VALUE`. Red against T011's code, which has no field value check and sends the value;
    - `WIRE-04 a max-value-length below 1 fails startup` (`ApplicationContextRunner`), parametrized over four cases:
      `boundrail.context.wire.max-value-length` set to `0` and to `-1`, each without and with lazy initialization
      (`spring.main.lazy-initialization=true` and `LazyInitializationBeanFactoryPostProcessor` added through `withInitializer`, as in T006a).
      `IllegalArgumentException` is the root cause. Red against T011's code, which accepts any `int`, in all four cases.
  - Green:
    - the field value check after the length check in `ContextClientInterceptor` ([plan.md](plan.md#outbound));
    - in `spring/BoundrailContextProperties.java`, the `Wire` compact constructor rejects a `maxValueLength` less than 1 with
      `IllegalArgumentException`. The `@Lazy(false)` reporter already binds the properties at startup, so no bean changes
      ([plan.md](plan.md#spring-wiring)).
  - Completes: WIRE-08 (only on values the interceptor writes); WIRE-04 (order; length in UTF-16 code units, not UTF-8 bytes; a
    `max-value-length` less than 1 fails startup, also under lazy initialization); OUT-04 (an application-set header's context value is not
    field-value-checked); DIAG-09 (outbound).
  - Verify: Unit `ContextClientInterceptorTest`.
- [x] T013 [US1] Topology for TrustBoundaryIT; enable IT-2.2 and IT-2.3 (guard tests).
  - Red (guard): enable IT-2.2 (edge calls partner through an `external()` client) and IT-2.3 (through an undeclared client to
    `partner.example.test`):
    - the edge endpoint binds `tenant`, `priority` and `INITIATOR` itself;
    - partner received none of `Tenant-Id`, `Priority`, `Boundrail-Initiator` or `Boundrail-Deadline-Ms`;
    - as a positive control in the same scenario, the same endpoint's internal call delivered `Tenant-Id: acme` to orders' headers.

    Both pass with T010's code. Show each red with `ContextClientInterceptor` temporarily treating every destination as `INTERNAL`, then revert
    ([plan.md](plan.md#guard-tests)).
  - Topology (test code, part of this task):
    - `topology/AppKeys.java`, and `topology/TopologyPolicy.java`, the topology table's policy as a `ContextPolicy` bean on every service;
    - `boundrail.context.destinations.internal-hosts=*.svc.test` on every service, in `topology/Topology.java`;
    - `topology/EdgeApp.java` endpoints calling orders and partner through internal, external and undeclared clients built from edge's
      `RestClient.Builder`.
  - Green: `integration-tests/build.gradle.kts` adds `spring-boot-starter-restclient` (catalog entry). No library change. `TopologySmokeIT`
    stays green.
  - Completes: IT-2.2; IT-2.3 (OUT-02, OUT-03, OUT-04, DEST-02, DEST-03 end to end).
  - Verify: IT.
- [x] T014 [US1] Forbidden headers on external calls; enable IT-2.4.
  - Red:
    - enable IT-2.4: edge application code sets `Tenant-Id: acme` and `Authorization: Bearer partner-key` on a call through an `external()`
      client. Partner receives the `Authorization` header and no `Tenant-Id`, edge's `FORBIDDEN_HEADER` count rises by exactly 1, and the call
      succeeds;
    - in `ContextClientInterceptorTest`:
      - `WIRE-07 an application-set header named after a key that does not propagate to EXTERNAL_CALL is removed on an external call`, also
        `Boundrail-Initiator` and `Boundrail-Deadline-Ms`. In the same request application code sets `Channel: mine`, whose key propagates
        to `EXTERNAL_CALL`, and the server receives only `Channel: mine`: the kept header is asserted next to the removals, which are red
        before this task, so it needs no guard of its own;
      - `WIRE-07 one FORBIDDEN_HEADER violation is reported per removed header`: `Tenant-Id` and `Boundrail-Initiator` give two violations,
        with keys `tenant` and `boundrail.initiator` (key names, not header names) and destination `EXTERNAL`, and the call continues in
        `fail` mode (DIAG-02);
      - `WIRE-07 Authorization is never removed`;
      - `DEST-03 an UNKNOWN destination is stripped like EXTERNAL`;
      - `WIRE-06 forbidden headers are matched ignoring case`: `tenant-id` is removed and reported with key `tenant`;
      - `WIRE-07 a removed header name with several values is reported once`: application code adds `Tenant-Id` twice, with two values, on
        an external call; partner receives neither value, and one `FORBIDDEN_HEADER` with key `tenant` is reported;
      - `WIRE-07 a forbidden header name with no values is removed and not reported`: on an `external()` call, application code puts
        `Tenant-Id` with no values. A name with no values is not sent on the wire, so the test adds a recording `ClientHttpRequestInterceptor`
        to the builder after the library's (`requestInterceptor(..)` on the customized builder) and asserts it sees no `Tenant-Id` name in
        `request.getHeaders().headerNames()`; no violation is reported. Red before this task, which strips nothing.
  - Green:
    - stripping in `ContextClientInterceptor` over the wire names of `policy.keys()`: each name present is removed, and one violation is
      reported per removed name that had at least one value (`getOrEmpty(name)` not empty);
    - topology test code: a `SimpleMeterRegistry` bean per service and `Running.violations(Service, Violation.Kind)` in `topology/Topology.java`;
    - `micrometer-core` in `integration-tests/build.gradle.kts`.

    Review note: interceptors registered after the library's can still add headers ([plan.md](plan.md#risks)).
  - Completes: WIRE-07 (including names with no values, and keeping headers of keys that propagate to `EXTERNAL_CALL`); OUT-04 (external);
    DEST-03 (stripping); WIRE-06; IT-2.4.
  - Verify: Unit `ContextClientInterceptorTest`; IT.
- [x] T015 [US1] Required keys before a remote call; enable IT-2.9.
  - Red:
    - enable IT-2.9: the edge endpoint binds `tenant`, runs `Context.without(tenant)` and calls orders through `internal("orders")`. It catches
      `ContextViolationException` and returns its kind. The response is `MISSING_REQUIRED`, edge's `MISSING_REQUIRED` count rises by exactly 1,
      and orders' observation count is unchanged;
    - in `ContextClientInterceptorTest`:
      - `POL-08 an unbound required key that propagates to INTERNAL_CALL is a MISSING_REQUIRED violation before sending`;
      - `DIAG-02 in fail mode the violation is reported, then ContextViolationException is thrown, and the request is not sent`:
        `RecordingServer` received nothing; the reporter recorded the violation before the exception left the interceptor; `violation()` has
        kind `MISSING_REQUIRED`, key `tenant`, boundary `INTERNAL_CALL` and the destination;
      - `DIAG-02 in report mode the violation is reported and the call continues without the key`;
      - `DIAG-02 violations.mode defaults to fail`;
      - `POL-08 a required key with only a default is MISSING_REQUIRED`;
      - `POL-08 a required key is MISSING_REQUIRED even when application code set its wire header`: the request is not sent;
      - `POL-08 a required key that does not propagate to the call's boundary is not checked`: an external call. This is a guard: nothing
        checks required keys before this task. Show it red with the propagation filter temporarily removed from the check, then revert
        ([plan.md](plan.md#guard-tests));
      - `POL-08 a required key that propagates to EXTERNAL_CALL is checked on an external or unknown call`;
      - `DIAG-02 several unbound required keys are each reported in ascending key-name order, and the exception carries the first`: a policy
        registering required `tenant`, then required `region`, both unbound on an internal call. In `fail` mode the reporter records
        `MISSING_REQUIRED` for `region`, then for `tenant`, before the exception, whose `violation()` has key `region`, and `RecordingServer`
        received nothing. In `report` mode the same two violations are recorded in the same order and the request is sent.
  - Green: the check in `ContextClientInterceptor` before any header is written and before `execute`: the unbound required keys sorted by
    name, each reported, then one exception carrying the first in `fail` mode; `violations.mode` in `BoundrailContextProperties`.
  - Completes: POL-08; DIAG-02; IT-2.9.
  - Verify: Unit `ContextClientInterceptorTest`; IT.

- [x] T015a [US1] Violation mode values and report-mode application headers (T015 review follow-up).
  - Blocked until spec.md states the two user decisions (DIAG-02, POL-08; [plan.md](plan.md#spec-gaps)).
  - Red, in `ContextClientInterceptorTest`. All three are expected to pass on T015's code, so each is a guard, shown red against its named
    temporary change, then reverted ([plan.md](plan.md#guard-tests)):
    - `DIAG-02 an invalid violations.mode fails startup` (`ApplicationContextRunner`), parametrized without and with lazy initialization (the
      T006a setup: `spring.main.lazy-initialization=true` and `LazyInitializationBeanFactoryPostProcessor` added through `withInitializer`).
      With `boundrail.context.violations.mode=bogus`, startup fails, and the failure's causes include a `BindException` for
      `boundrail.context.violations.mode`. Guard: show it red with an unconvertible `mode` temporarily defaulted to `FAIL` instead of failing
      (for example `mode` bound as a `String` and mapped to `FAIL` unless it equals `report`);
    - `DIAG-02 violations.mode is matched ignoring case and an empty value means fail`, parametrized: `REPORT` and `Report` give report
      mode (the violation is reported and the request is sent), `FAIL` gives fail mode, and an empty value gives fail mode
      (`ContextViolationException`, nothing sent). Guard: show the case rule red with `mode` temporarily bound as a `String` and compared with
      `equals("report")`; show the empty-value rule red with `@DefaultValue("fail")` temporarily removed from `mode`, so `""` binds to
      `null` and the interceptor does not fail;
    - `DIAG-02 in report mode an application-set header of an unbound required key is sent`: with `violations.mode=report` and `tenant`
      required and unbound, application code sets `Tenant-Id: mine` on an internal call. One `MISSING_REQUIRED` for `tenant` is reported,
      the call continues, and the server receives `Tenant-Id: mine`. Guard: show it red with the interceptor temporarily removing, in
      `report` mode, the wire header of each missing required key, then revert.
  - Green: no production code. If a test is red, the minimum change that makes it pass, in `spring/BoundrailContextProperties.java`
    (`Violations`) or `spring/ContextClientInterceptor.java`, and the test is then not a guard: the leader reports it and the planner removes its
    Guard Tests row.
  - Completes: DIAG-02 (invalid mode fails startup, also under lazy initialization; values match ignoring case; empty means `fail`; in
    `report` mode an application-set header of an unbound required key is sent); POL-08 (same `report`-mode rule).
  - Verify: Unit `ContextClientInterceptorTest`; `./gradlew check`.

**Checkpoint**: IT-2.2, IT-2.3, IT-2.4 and IT-2.9 green on JDK 21 and JDK 25.

---

## Phase 5: User Story 2 - Forged context is rejected at the edge (Priority: P1)

**Goal**: Context headers are bound only from trusted callers, and checked for repetition, length and decoding. At the edge, context comes from
authentication through contributors.

**Independent Test**: IT-2.5 – IT-2.8 in the IT command. IT-2.1 (US1) is enabled here, in T028, because it needs this story's inbound path.

- [x] T016 [US2] Inbound filter, trusted binding and the default verifier.
  - Red:
    - in `springtest/ContextInboundFilterTest.java` (`MockHttpServletRequest`, and a `MockFilterChain` whose servlet records `find()` of each
      key):
      - `IN-01 ContextInboundFilter is registered before Spring Security's filter chain`: its `FilterRegistrationBean` order is lower than
        Boot's `SecurityFilterProperties.DEFAULT_FILTER_ORDER`;
      - `IN-01 the rest of the chain runs inside the scope and the previous snapshot is restored afterwards`, also when the chain throws
        `ServletException`, which is rethrown unchanged;
      - `IN-02 a trusted request binds every key that propagates to INTERNAL_CALL and whose header is present`: `tenant` and `priority`
        decoded with their codecs;
      - `IN-02 keys not propagating to INTERNAL_CALL are not bound from headers`, with `tenant` bound in the same request;
      - `WIRE-01 inbound values are read from the header named by the wire name`;
      - `WIRE-06 inbound header names match ignoring case` (`tenant-id`);
      - `IN-03 an InboundTrustVerifier bean replaces the built-in verifier`: a bean returning `TRUSTED_INTERNAL`;
    - in `springtest/InboundTrustVerifierTest.java`:
      - `IN-03 the default verifier returns UNTRUSTED for every request`: with no property, and with `inbound.trust=none`; with the default
        verifier, a request carrying `Tenant-Id` binds nothing, while the same request through a trusting bean binds it;
      - `CORE-13 the default verifier rejects a null request`.
  - Green:
    - `spring/InboundTrustVerifier.java` and `spring/ContextInboundFilter.java`;
    - the filter's registration (order `-101`) and the default verifier bean (`@ConditionalOnMissingBean`) in a nested
      `@ConditionalOnWebApplication(type = SERVLET)` configuration;
    - `inbound.trust` in `BoundrailContextProperties`;
    - `compileOnly` and `testImplementation` for `jakarta.servlet-api`, and `testImplementation` for `spring-boot-security` (for the order
      constant).
  - Completes: IN-01; IN-02 (trusted binding); IN-03 (default verifier, bean replaces); WIRE-01 (inbound); WIRE-06 (inbound); CORE-13 (default
    verifier).
  - Verify: Unit `ContextInboundFilterTest` and `InboundTrustVerifierTest`.
- [x] T017 [US2] Built-in keys inbound, the filter order and the T016 review follow-ups.
  - Red, in `ContextInboundFilterTest`:
    - `WIRE-02 a trusted Boundrail-Initiator header binds the initiator with that value`;
    - `IN-02 a trusted request binds no deadline and no authority from headers`: with `Boundrail-Initiator`, `Boundrail-Deadline-Ms` and
      `Authorization` present, only the initiator is bound;
    - `IN-01 the inbound filter runs before Spring Security at a configured spring.security.filter.order`: with
      `spring.security.filter.order=-200`, the registration order is `-201`. Red against T016's fixed `-101`. With no property it is `-101`,
      which T016's IN-01 test already covers;
    - `IN-01 an undeclared checked exception from the chain is rethrown unchanged`: the chain sneaky-throws a checked exception that is
      neither `IOException` nor `ServletException`, on a trusted request that binds `tenant` and on a request with nothing bound; the same
      instance comes out of the filter in both. Red on the trusted path, where T016 wraps it in `UndeclaredThrowableException`;
    - `IN-01 no inbound filter or verifier is registered outside a servlet web application`: a plain `ApplicationContextRunner` with the
      auto-configuration has no `FilterRegistrationBean` and no `InboundTrustVerifier` bean. This is a guard: T016's nested configuration is
      already `@ConditionalOnWebApplication(type = SERVLET)`. Show it red with that annotation temporarily removed, then revert
      ([plan.md](plan.md#guard-tests)).
  - Green:
    - `INITIATOR` decoded with `Initiator.of`; `DEADLINE` and `AUTHORITY` skipped;
    - in `BoundrailContextAutoConfiguration.InboundConfiguration`, the `FilterRegistrationBean` order is `securityOrder - 1`, where
      `securityOrder` is `spring.security.filter.order` read from the `Environment` as an `int` with a default of `-100`.
      `ContextInboundFilter.ORDER` (`-101`) is replaced by `SECURITY_FILTER_ORDER_DEFAULT = -100`, Boot's
      `SecurityFilterProperties.DEFAULT_FILTER_ORDER` copied because Spring Security is optional, with its Javadoc saying so;
    - in `ContextInboundFilter`, the `catch (Exception e)` around `binding.call` rethrows unchanged through a generic sneaky-rethrow helper
      (`<E extends Throwable> void sneakyThrow(Throwable t) throws E`), replacing the `UndeclaredThrowableException` wrap and its
      "unreachable" comment;
    - `doFilterInternal`'s Javadoc, which says "Keys without a codec, the built-in keys, are not bound", now says the initiator is bound and
      the deadline and authority are not.
  - Completes: WIRE-02 (inbound); IN-02 (built-in keys); IN-01 (configured security order, undeclared checked exceptions, servlet only).
  - Verify: Unit `ContextInboundFilterTest`.
- [x] T018 [US2] Repeated headers on a trusted request, an invalid security filter order and the T017 review follow-ups.
  - Red, in `ContextInboundFilterTest`:
    - `IN-02 a repeated policy header on a trusted request leaves the key unbound and reports one INVALID_VALUE`: `Tenant-Id: a` and
      `Tenant-Id: b` leave `tenant` unbound, give one `INVALID_VALUE`, and `priority` from the same request is bound. A recording codec shows
      `decode` was not called;
    - `WIRE-06 repeated headers are counted ignoring case`: `Tenant-Id` and `tenant-id`;
    - `DIAG-08 an inbound violation has boundary INTERNAL_CALL and no destination`;
    - `DIAG-02 a repeated header does not fail the request in fail mode`;
    - `IN-02 a repeated Boundrail-Deadline-Ms on a trusted request is skipped, not reported`: two `Boundrail-Deadline-Ms` values give no
      `INVALID_VALUE` and bind nothing, so the `DEADLINE`/`AUTHORITY` skip is checked before the repetition check (T017 review, Minor 1). This
      is a guard: before this task nothing reports a repeated header. Show it red on this task's finished code with the `DEADLINE`/`AUTHORITY`
      skip temporarily removed, so the deadline header reaches the repetition check and gives `INVALID_VALUE`, then revert
      ([plan.md](plan.md#guard-tests));
    - `IN-01 a spring.security.filter.order that is not an integer or is Integer.MIN_VALUE fails startup` (user decision, T017 review):
      parametrized over `abc` and `-2147483648`, each without and with lazy initialization (the T006a setup:
      `spring.main.lazy-initialization=true` and `LazyInitializationBeanFactoryPostProcessor` through `withInitializer`). Startup fails, with an
      `IllegalArgumentException` as the root cause (for `abc`, the `NumberFormatException` of the `Integer` conversion).
      - `-2147483648`, both cases: red against T017's code, where `securityOrder - 1` wraps to `Integer.MAX_VALUE` and startup succeeds;
      - `abc` with lazy initialization: red against T017's code, whose registration bean is lazy, so the property is never read in a runner
        with no embedded server;
      - `abc` without lazy initialization: a guard, because the `Environment`'s `Integer` conversion already throws. Show it red with the
        property temporarily read as a `String` and the default `-100` used when it doesn't parse as an `int`, then revert
        ([plan.md](plan.md#guard-tests)).
  - Green:
    - the repetition check through `getHeaders` in `ContextInboundFilter`, after the `DEADLINE`/`AUTHORITY` skip and before the codec lookup,
      and reporting through `ViolationReporter`;
    - in `BoundrailContextAutoConfiguration.InboundConfiguration`, the `boundrailInboundFilter` registration bean throws
      `IllegalArgumentException` when `securityOrder` is `Integer.MIN_VALUE`, and is `@Lazy(false)`, so both failures occur at startup under
      lazy initialization ([plan.md](plan.md#spring-wiring)).
  - Refactor (T017 review, Minor 2): `ContextInboundFilter.sneakyThrow` becomes `private`. `ContextInboundFilterTest` keeps its own
    `sneakyThrow`, because its chain calls it to throw the undeclared checked exception and can't reach the filter's private one. The IN-01
    undeclared-checked-exception test stays green.
  - Completes: IN-02 (repeated trusted headers; built-in keys skipped before the repetition check); DIAG-08; WIRE-06; IN-01 (invalid
    `spring.security.filter.order` fails startup, also under lazy initialization).
  - Blocked until spec.md's IN-01 text on an invalid `spring.security.filter.order` is committed ([plan.md](plan.md#spec-gaps)).
  - Verify: Unit `ContextInboundFilterTest`.
- [x] T019 [US2] Untrusted headers are reported.
  - Red, in `ContextInboundFilterTest` (default verifier, except the trusted comma test):
    - `IN-02 one UNTRUSTED_HEADER violation is reported per policy wire header present`: `Tenant-Id`, `Priority` and `Boundrail-Initiator` give
      three violations, with keys `tenant`, `priority` and `boundrail.initiator` (key names, not header names); nothing is bound;
    - `IN-02 a repeated untrusted header is reported once`: two `Tenant-Id` values, or `Tenant-Id` and `tenant-id`, give one
      `UNTRUSTED_HEADER`, with key `tenant`;
    - `DIAG-08 an untrusted header violation has boundary INTERNAL_CALL and no destination`;
    - `IN-02 untrusted headers never fail the request`: the chain runs, in the default `fail` mode (DIAG-02);
    - `IN-02 every wire name of the effective policy's keys is reported, not only those the trusted path binds`: `Boundrail-Deadline-Ms`
      gives one `UNTRUSTED_HEADER` with key `boundrail.deadline`, and the header of a key that propagates only to `MESSAGE` gives one for that key;
    - `IN-02 Authorization is not a policy wire header`: `Authorization: Bearer x` with `Tenant-Id` gives exactly one `UNTRUSTED_HEADER`,
      for `tenant`;
    - `IN-02 an empty untrusted policy header is reported`: `Tenant-Id: ""` gives one `UNTRUSTED_HEADER`, with key `tenant`, because an
      empty header is present (IN-02, b864076);
    - `IN-02 a single header line with commas is one value and is not split`: on a trusted request, `Tenant-Id: a, b` binds `tenant` to
      `"a, b"` with the string codec, and no violation is reported. This is a guard: T018's repetition check counts header lines, not commas.
      Show it red with `ContextInboundFilter.bind` temporarily splitting each header value on commas and treating more than one part as a
      repeat (so `tenant` is unbound with one `INVALID_VALUE`), then revert ([plan.md](plan.md#guard-tests)).
  - Green: reporting in the `UNTRUSTED` branch of `ContextInboundFilter`, over the wire names of every key in `policy.keys()`.
  - Completes: IN-02 (untrusted, including an empty header; a single comma-containing line is one value); DIAG-08.
  - Blocked until spec.md's IN-02 text on a single comma-containing header line is committed ([plan.md](plan.md#spec-gaps)).
  - Verify: Unit `ContextInboundFilterTest`.
- [x] T020 [US2] Test trust verifier; enable IT-2.7.
  - Red:
    - enable IT-2.7: edge binds `tenant=acme` and calls orders through `internal("orders")` with `peer()` applied. Orders records
      `ctx.tenant=acme`;
    - in `testmodtest/TestInboundTrustVerifierTest.java`:
      - `TEST-01 TRUSTED_INTERNAL exactly when Boundrail-Test-Peer carries the secret once`: absent, wrong value, correct value, and the
        correct value twice (`UNTRUSTED`);
      - `TEST-01 every instance shares one secret`: a request made through `peer()` is trusted by two separately constructed verifiers;
      - `TEST-01 peer() adds the header to every request of the client it configures`: two real calls to a JDK `HttpServer`; a client without
        `peer()` sends no such header;
      - `TEST-01 the verifier is not auto-configured`: neither the verifier nor `peer()` is reachable from any auto-configuration imports file
        or `spring.factories` on the test module's classpath;
      - `CORE-13 the test verifier rejects a null request`.
  - Green:
    - `testmod/TestInboundTrustVerifier.java` ([plan.md](plan.md#inbound));
    - `boundrail-context-test/build.gradle.kts`: `compileOnly` and `testImplementation` for `-spring`, `spring-web` and `jakarta.servlet-api`,
      `testImplementation` for `spring-boot-restclient`;
    - topology test code: the verifier bean on every service, `peer()` on edge's internal clients only, and `ctx.tenant` and `ctx.priority`
      recorded by every `/ping`.
  - Completes: TEST-01; CORE-13 (test verifier); IT-2.7 (IN-02, IN-03 end to end).
  - Verify: Unit `TestInboundTrustVerifierTest` in `:boundrail-context-test`; IT.
- [x] T020a [US2] Test module dependency scopes and the T020 review follow-ups.
  - Red, in `integration-tests/src/test/java/io/github/boundrail/context/it/BuildSmokeIT.java`:
    - `TEST-01 boundrail-context-test publishes boundrail-context-spring as an api dependency and no spring-web or servlet API`. Like the
      existing BuildSmokeIT tests, it reads a build output of a library project, and like `BLD-06 runs on the JDK its task declares`, it gets
      the files' locations from system properties set by the integration test task, asserting first that each is set:
      - the generated POM (`boundrail.it.test-module.pom`), parsed with the JDK's `DocumentBuilderFactory`: a `<dependency>` with artifactId
        `boundrail-context-spring` and scope `compile`; no dependency with artifactId `spring-web` or `jakarta.servlet-api`;
      - the generated Gradle module metadata (`boundrail.it.test-module.metadata`, `module.json`), parsed with Jackson's `JsonMapper`, already on
        the integration-tests classpath through `spring-boot-starter-webmvc`: the `apiElements` variant lists module
        `boundrail-context-spring`; no variant lists `spring-web` or `jakarta.servlet-api`.

      Red today, because `-spring` is `compileOnly`, so neither file names it. The absence of `spring-web` and the servlet API is asserted in
      the same test as the red presence assertion, so it needs no guard of its own.
    - `TEST-01 the boundrail-context-test JAR ships no AutoConfiguration.imports and no spring.factories`: the JAR is located by the code source
      of `TestInboundTrustVerifier`, as `BLD-06 library classes load from packaged JARs` locates core's, and opened with `JarFile`; it has no
      `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` and no `META-INF/spring.factories` entry. This
      tightens T020's `TEST-01 the verifier is not auto-configured`, whose scan matches only files naming the verifier's package. It sits in
      BuildSmokeIT because only there is the code source the module's JAR: in `:boundrail-context-test:test` it is `build/classes/java/main`,
      while resources are in `build/resources/main`, so a code-source check there could never be red ([plan.md](plan.md#integration-tests)).
      This is a guard: show it red with a temporary
      `boundrail-context-test/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` listing
      `io.github.boundrail.context.spring.BoundrailContextAutoConfiguration`, a class in another package, which T020's test does not catch,
      then revert ([plan.md](plan.md#guard-tests)).
  - Red, in `TrustBoundaryIT` (T020 review follow-up):
    - IT-2.2 and IT-2.3 also assert that partner received no `boundrail-test-peer` header (`doesNotContainKey`, as observations use lower-case
      names). These are guards: T020 applies `peer()` to edge's internal clients only. Show IT-2.2 red with `peer()` temporarily applied to
      edge's `externalPartner` client, and IT-2.3 red with it temporarily applied to `undeclaredPartner`, in `topology/EdgeApp.java`, then
      revert ([plan.md](plan.md#guard-tests)).
  - Test harness (build code, part of this task, proved by the first red test):
    - `boundrail-context-test/build.gradle.kts`: the `maven-publish` plugin and one publication, `maven`, `from(components["java"])`. Nothing
      is published; the POM and `module.json` are generated only ([plan.md](plan.md#build));
    - `integration-tests/build.gradle.kts`: every `Test` task depends on `:boundrail-context-test:generatePomFileForMavenPublication` and
      `:boundrail-context-test:generateMetadataFileForMavenPublication`, declares their output files as inputs, and passes their paths
      (`boundrail-context-test/build/publications/maven/pom-default.xml` and `module.json`) as the two system properties.
  - Green: in `boundrail-context-test/build.gradle.kts`, `compileOnly(project(":boundrail-context-spring"))` becomes
    `api(project(":boundrail-context-spring"))`, and the now redundant `testImplementation(project(":boundrail-context-spring"))` is removed.
    `spring-web` and `jakarta.servlet-api` stay `compileOnly`.
  - Completes: TEST-01 (dependency scopes; no auto-configuration file in the `-test` JAR); IT-2.2 and IT-2.3 (no test peer header reaches
    partner).
  - Verify: IT (`./gradlew --continue :integration-tests:integrationTestJdk21 --tests '*BuildSmokeIT' --tests '*TrustBoundaryIT'
    :integration-tests:integrationTestJdk25 --tests '*BuildSmokeIT' --tests '*TrustBoundaryIT'`); `./gradlew check`.
- [x] T021 [US2] CIDR verifier.
  - Red, in `springtest/CidrInboundTrustVerifierTest.java`:
    - `IN-03 with inbound.trust=cidr a remote address inside a trusted CIDR is TRUSTED_INTERNAL`: IPv4 `10.0.0.0/8` with `10.1.2.3`; IPv6
      `fd00::/8` with `fd00::1`;
    - `IN-03 a remote address outside every trusted CIDR is UNTRUSTED`;
    - `IN-03 the checked address is getRemoteAddr()`: remote address `203.0.113.5` with `X-Forwarded-For: 10.0.0.1` is `UNTRUSTED`;
    - `IN-03 trusted-cidrs defaults to empty, so cidr trusts nobody`;
    - `IN-03 a verifier bean wins over inbound.trust=cidr`;
    - `IN-08 an IPv4 remote address matches only IPv4 entries and an IPv6 address only IPv6 entries`: `10.1.2.3` against `::/0` is
      `UNTRUSTED`; `fd00::1` against `0.0.0.0/0` is `UNTRUSTED`;
    - `IN-08 an IPv4-mapped remote address is matched as IPv4`: `::ffff:10.1.2.3` against `10.0.0.0/8` is `TRUSTED_INTERNAL`;
    - `CORE-13 the CIDR verifier rejects a null request`;
    - `IN-08 a remote address that is not an IP literal is UNTRUSTED`, parametrized over `unknown`, `orders.svc.test` and the empty string,
      with `0.0.0.0/0` and `::/0` both trusted;
    - `IN-08 the remote address is parsed without DNS lookups`: `localhost`, which would resolve to `127.0.0.1`, against `127.0.0.0/8` is
      `UNTRUSTED`;
    - `IN-08 an over-long remote address is UNTRUSTED without costly parsing`: against `::/0`, a 39-character IPv6 literal is
      `TRUSTED_INTERNAL`, and `":".repeat(8191) + "%"` is `UNTRUSTED` within 200 ms (`assertTimeoutPreemptively`);
    - `IN-08 an IPv6 remote address with a zone ID is UNTRUSTED`: against `fe80::/10`, `fe80::1%eth0` is `UNTRUSTED` and `fe80::1` is
      `TRUSTED_INTERNAL`.
  - Green: package-private `spring/CidrInboundTrustVerifier.java` for `address/prefix` entries, `inbound.trusted-cidrs`, and selection by
    `inbound.trust`. The remote address goes through the same literal check as entries ([plan.md](plan.md#inbound)); one that fails it matches
    nothing.
  - Completes: IN-03 (CIDR, non-literal remote address); IN-08 (matching, remote address parsing); CORE-13 (CIDR verifier).
  - Verify: Unit `CidrInboundTrustVerifierTest`.
- [ ] T022 [US2] Strict CIDR literals and `inbound.trust` values.
  - Red, in `CidrInboundTrustVerifierTest`:
    - `IN-08 a bare address is a single host`: `10.1.2.3` trusts `10.1.2.3` and not `10.1.2.4`; `fd00::1` trusts only itself;
    - `IN-08 prefix lengths are 0 to 32 for IPv4 and 0 to 128 for IPv6`: `0.0.0.0/0` and `::/0` are accepted. This is a guard: T021 accepts
      every prefix. Show it red with the prefix range check temporarily starting at 1, then revert ([plan.md](plan.md#guard-tests));
    - `IN-08 an invalid entry fails startup`, parametrized: `10.0.0.1/8` (host bits), `10.0.0.0/33`, `fd00::/129`, `10.0.0.0/-1`, `10.0.0.0/`,
      `::ffff:10.0.0.0/104`, `::ffff:10.0.0.1`, `fe80::1%eth0`, `localhost`, `orders.svc.test` and `10.0.0`; in square brackets,
      `[2001:db8::1]` and `[2001:db8::]/32`; and breaking the decimal form, `010.0.0.1`, `10.0.0.256`, `::ffff:10.0.0.01`, `::10.0.0.01/128`,
      `10.0.0.0/08` and `10.0.0.0/+8`. Each fails startup with `inbound.trust=cidr`. Eleven cases already fail on T021's code: `10.0.0.0/`,
      whose empty prefix `Integer.parseInt` rejects, and `::ffff:10.0.0.1`, `fe80::1%eth0`, `localhost`, `orders.svc.test`, `10.0.0`,
      `[2001:db8::1]`, `[2001:db8::]/32`, `010.0.0.1`, `10.0.0.256` and `::ffff:10.0.0.01`, which have no `/` or fail T021's literal check.
      The other eight are red. For those eleven cases this is a guard: show them red with the `CidrInboundTrustVerifier` constructor
      temporarily skipping an entry whose parsing throws `IllegalArgumentException`, then revert ([plan.md](plan.md#guard-tests));
    - `IN-08 entries are parsed without DNS lookups`: `localhost`, which would resolve, fails startup. This is a guard: T021 rejects an entry
      without a `/`. Show it red with the textual literal check temporarily skipped for entries, so `localhost` reaches
      `InetAddress.getByName`, resolves to `127.0.0.1` and is accepted as a bare address, then revert ([plan.md](plan.md#guard-tests));
    - `IN-08 invalid entries are ignored when inbound.trust is not cidr`: startup succeeds with `trust=none`. This is a guard: T021 creates the
      CIDR verifier only with `cidr`. Show it red with the verifier bean method temporarily building a `CidrInboundTrustVerifier` from
      `trusted-cidrs` before it switches on `trust`, then revert ([plan.md](plan.md#guard-tests));
    - `IN-08 an invalid CIDR entry fails startup with lazy initialization`: `inbound.trust=cidr` and `trusted-cidrs=10.0.0.1/8` with
      `spring.main.lazy-initialization=true` and `LazyInitializationBeanFactoryPostProcessor` added through `withInitializer`, as in T006a;
      `IllegalArgumentException` is the root cause;
    - `IN-08 one enclosing pair of square brackets is removed from the remote address`: against `2001:db8::/32`, `[2001:db8::1]` is
      `TRUSTED_INTERNAL`, and `[[2001:db8::1]]` and `[2001:db8::1` are `UNTRUSTED`;
    - `IN-08 a remote address whose IPv4 part breaks the decimal form is UNTRUSTED`: with `0.0.0.0/0` and `::/0` trusted, `10.0.0.1` is
      `TRUSTED_INTERNAL`, and `010.0.0.1`, `10.0.0.256`, `::ffff:10.0.0.01` and `::10.0.0.01` are `UNTRUSTED`. It is red on T021's code,
      because `InetAddress.getByName` accepts `::ffff:10.0.0.01` (as `10.0.0.1`) and `::10.0.0.01`; T021's dotted-quad pattern already
      rejects `010.0.0.1` and `10.0.0.256`, which sit in the same test as these red assertions;
    - `IN-03 inbound.trust is matched with relaxed binding`: with `trusted-cidrs=10.0.0.0/8`, both `CIDR` and ` Cidr ` select the CIDR
      verifier, so `10.1.2.3` is `TRUSTED_INTERNAL`. ` Cidr ` is set through a `MapPropertySource` added with `withInitializer`, because
      `withPropertyValues` trims values. It is red on T021's code: `@ConditionalOnProperty` compares with `equalsIgnoreCase`, so ` Cidr `
      selects no verifier and startup fails;
    - `IN-03 an empty or blank inbound.trust means none`: `""` and `" "` (the blank one through a `MapPropertySource`), with
      `trusted-cidrs=0.0.0.0/0`, start up with the default verifier, so `10.1.2.3` is `UNTRUSTED`. It is red on T021's code: neither
      `@ConditionalOnProperty` matches a present empty value, so startup fails;
    - `IN-03 an invalid inbound.trust fails startup with a binding error naming the property`, parametrized: without lazy initialization,
      with it (the T006a setup), and with an application `InboundTrustVerifier` bean. `bogus` fails startup, and a `BindException` in the
      cause chain names `boundrail.context.inbound.trust`, as in T015a's DIAG-02 test. It is expected to pass on T021's code, because the
      `@Lazy(false)` reporter and client customizers bind `BoundrailContextProperties` at startup, so it is a guard: show it red with
      `Inbound.trust` temporarily bound as a `String` and mapped to `NONE` unless it equals `cidr` ignoring case, then revert
      ([plan.md](plan.md#guard-tests)). If a case is red on T021's code, the test is not a guard: its green is the bean method below, the
      leader reports it, and the planner removes its Guard Tests row;
    - change T021's `IN-08 an over-long remote address is UNTRUSTED without costly parsing` to use `":".repeat(65535) + "%"`, so its red margin
      holds on faster machines. It passes before this task's code, so it is a guard: show it red with the 45-character cap temporarily removed
      and the IPv6 pattern temporarily the backtracking `[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*`, then revert ([plan.md](plan.md#guard-tests)).
  - Green:
    - strict parsing in `CidrInboundTrustVerifier` ([plan.md](plan.md#inbound)): bare addresses, prefix form and range, host bits, no
      IPv4-mapped entries, the decimal form of the IPv4 part of an IPv6 literal, and one enclosing pair of square brackets removed from the
      remote address only;
    - in `BoundrailContextAutoConfiguration`, the two `@ConditionalOnProperty` verifier beans replaced by one `@ConditionalOnMissingBean`,
      `@Lazy(false)` bean method, `boundrailInboundTrustVerifier`, that switches on the bound `Trust`, so the CIDR verifier is built, and its
      entries parsed, at startup under lazy initialization.
  - Completes: IN-03 (relaxed, blank and invalid `inbound.trust`, also under lazy initialization and with a verifier bean); IN-08 (entries,
    square brackets, decimal form, startup failure, also under lazy initialization).
  - Verify: Unit `CidrInboundTrustVerifierTest`.
- [ ] T023 [US2] Inbound length limit.
  - Red, in `ContextInboundFilterTest`:
    - `WIRE-04 a trusted value longer than max-value-length is not bound and is reported as OVERSIZED_VALUE`: a 300-character value leaves
      `tenant` unbound and gives one `OVERSIZED_VALUE`, with boundary `INTERNAL_CALL` and no destination (DIAG-08). The chain runs in `fail`
      mode (DIAG-02). Neither the violation nor the log entry contains the value (DIAG-09);
    - `WIRE-04 a trusted value of exactly max-value-length is bound`.
  - Green: the length check in `ContextInboundFilter` before the value reaches the codec.
  - Completes: WIRE-04 (inbound); DIAG-08; DIAG-09 (oversized inbound).
  - Verify: Unit `ContextInboundFilterTest`.
- [ ] T024 [US2] Decode failures; enable IT-2.8.
  - Red:
    - enable IT-2.8, with `@ExtendWith(OutputCaptureExtension.class)`. A trusted caller (the test's own `RestClient`, with `peer()`) sends
      requests to orders:
      - `Priority: abc`: `ctx.priority` is absent, orders' `INVALID_VALUE` count rises by 1, and the status is 200;
      - a 300-character `Tenant-Id`: `ctx.tenant` is absent, `OVERSIZED_VALUE` rises by 1, and the status is 200;
      - the captured violation log entries for `priority` and `tenant` are present and contain neither `abc` nor the 300-character value. Nor
        do the counter's tags. The `Violation` records are asserted by the unit tests of WIRE-04 (T011, T023) and WIRE-05 (this task), as
        DIAG-09 says;
    - in `ContextInboundFilterTest`:
      - `WIRE-05 a trusted value that fails to decode is not bound and is reported as INVALID_VALUE`: also an empty `Boundrail-Initiator`;
      - `WIRE-05 the request continues after a decode failure`, in `fail` mode (DIAG-02);
      - `DIAG-09 a decode failure is reported without the value or the codec's message`: `Codecs.longValue()` with `abc`, whose exception
        message echoes the input. The violation and the log entry contain neither `abc` nor the message, and the log entry names `priority`,
        `Priority` and `INTERNAL_CALL`;
      - `DIAG-08 a decode failure has boundary INTERNAL_CALL and no destination`;
      - `WIRE-04 an inbound value both oversized and undecodable is reported once, as OVERSIZED_VALUE, without calling the codec`. This is a
        guard: show it red with `decode` temporarily called before the length check, then revert ([plan.md](plan.md#guard-tests));
      - `WIRE-05 a subclass of IllegalArgumentException from decode is a decode failure`: an application codec throwing
        `NumberFormatException` leaves the key unbound, gives one `INVALID_VALUE`, and the chain runs;
      - `WIRE-05 any other exception from decode propagates unchanged and no violation is reported`: an application codec throwing
        `IllegalStateException`; `doFilter` throws the same instance, the chain does not run, and the reporter recorded nothing. This is a
        guard: show it red with the catch temporarily widened to `RuntimeException`, then revert ([plan.md](plan.md#guard-tests));
      - `WIRE-05 a single comma-containing value that the codec rejects is INVALID_VALUE`: on a trusted request, `Priority: 1, 2` (one header
        line, `Codecs.longValue()`) leaves `priority` unbound, gives one `INVALID_VALUE`, and the chain runs (IN-02: decoded whole, not split).
  - Green: catch `IllegalArgumentException` (and only it) from `decode` in `ContextInboundFilter` and report without the value or the
    message; `spring-boot-test` (`testImplementation`, catalog entry) in `integration-tests/build.gradle.kts`.
  - Blocked until spec.md's IN-02 text on a single comma-containing header line is committed ([plan.md](plan.md#spec-gaps)).
  - Completes: WIRE-05 (including a single comma-containing value); WIRE-04 (before decode); DIAG-08; DIAG-09 (decode failures); IT-2.8.
  - Verify: Unit `ContextInboundFilterTest`; IT.
- [ ] T025 [US2] Authentication filter and contributors.
  - Red, in `springtest/ContextAuthenticationFilterTest.java`:
    - `IN-04 ContextAuthenticationFilter is registered after Spring Security's filter chain when Spring Security is present`: its order is
      higher than `DEFAULT_FILTER_ORDER`;
    - `IN-04 it is not registered without Spring Security` (`FilteredClassLoader`);
    - `IN-04 for an authenticated request every ContextContributor bean is invoked and the rest of the chain runs with the contributed values`;
    - `IN-04 the contributed values are bound only for the rest of the chain`;
    - `IN-04 contributors are not invoked without an authentication, or when isAuthenticated() is false`, with an authenticated request in the
      same test as a positive control;
    - `IN-04 an anonymous authentication is not authenticated`: an `AnonymousAuthenticationToken` invokes no contributor;
    - `IN-05 a contributed value does not replace a key already bound`: the key is bound by a trusted header through `ContextInboundFilter`;
    - `IN-05 the first contribution for a key wins`, from two contributors ordered with `@Order`, and from one contributor calling `put` twice;
    - `IN-05 contributors are invoked in bean order`;
    - `CORE-13 ContextContributions.put rejects null and values of the wrong type`: a `null` key or value throws `NullPointerException`; a value
      that is not an instance of the key's type (through a raw cast) throws `ClassCastException` at `put`.
  - Green:
    - `spring/ContextContributor.java`, `spring/ContextContributions.java` (`put` only), `spring/ContextAuthenticationFilter.java`;
    - its registration (order `-99`) in a nested `@ConditionalOnClass(SecurityContextHolder.class)` configuration;
    - `testImplementation` for `spring-security-web`.

    The `AUTHORITY` clause of IN-05 is delivered with ID-02 in M5.
  - Completes: IN-04; IN-05; CORE-13 (`put`).
  - Verify: Unit `ContextAuthenticationFilterTest`.
- [ ] T026 [US2] Contributions can report invalid values.
  - Red, in `ContextAuthenticationFilterTest`:
    - `IN-07 invalid(key) reports one INVALID_VALUE`, with boundary `INTERNAL_CALL` and no destination (DIAG-08); the chain runs in `fail` mode
      (DIAG-02);
    - `IN-07 invalid(key) binds nothing`;
    - `IN-07 invalid(key) does not affect contributions for the same key`: `put` then `invalid` binds the value; `invalid` then `put` binds the
      value; in both cases one `INVALID_VALUE` is reported;
    - `CORE-13 ContextContributions.invalid(null) throws NullPointerException`.
  - Green: `invalid` in `ContextContributions` and its implementation in `ContextAuthenticationFilter`.
  - Completes: IN-07; DIAG-08 (authentication values); CORE-13 (`invalid`).
  - Verify: Unit `ContextAuthenticationFilterTest`.
- [ ] T027 [US2] JWT claim contributor; enable IT-2.5, IT-2.1 and IT-2.6 (guard).
  - Red:
    - enable IT-2.5: a public caller with alice's JWT sends `Tenant-Id: evil` to edge. Edge records `ctx.tenant=acme`, edge's
      `UNTRUSTED_HEADER` count rises by 1, and the status is 200;
    - enable IT-2.1: a public request with alice's JWT; edge, binding nothing itself, calls orders through `internal("orders")`. Orders records
      `ctx.tenant=acme`.

      Both are red until edge contributes the claim;
    - enable IT-2.6: with a JWT from `TestJwts.withoutTenant()` and `Tenant-Id: evil`, edge records no `ctx.tenant`. This is a guard: show it
      red with edge's verifier temporarily replaced by one trusting every request, then revert ([plan.md](plan.md#guard-tests));
    - in `springtest/JwtClaimContributorTest.java`:
      - `IN-06 the named claim of a JWT authentication is decoded with the codec and contributed`;
      - `IN-06 a missing claim leaves the key unbound and reports nothing`;
      - `IN-06 a non-JWT authentication contributes nothing`;
      - `CORE-13 JwtClaimContributor rejects null`: `of` with a `null` argument, and `contribute` with a `null` argument, throw
        `NullPointerException`.
  - Green:
    - `spring/JwtClaimContributor.java`, for valid and missing claims;
    - `compileOnly` and `testImplementation` for `spring-security-oauth2-resource-server` and `spring-security-oauth2-jose`;
    - topology test code: edge's `JwtClaimContributor.of("tenant", TENANT, Codecs.string())` bean, and `TestJwts.withoutTenant()`.
  - Completes: IN-06 (valid and missing claims); CORE-13 (`JwtClaimContributor`); IT-2.1; IT-2.5; IT-2.6 (IN-02, IN-04, IN-05 end to end).
  - Verify: Unit `JwtClaimContributorTest`; IT.
- [ ] T028 [US2] Invalid claims.
  - Red, in `JwtClaimContributorTest`:
    - `IN-06 a claim whose value is not a String is reported as INVALID_VALUE`, parametrized over a number, a boolean and a list: the key stays
      unbound and `invalid(key)` is called once;
    - `IN-06 a claim that fails to decode is reported as INVALID_VALUE`: a `longValue()` contributor with claim `abc`, through
      `ContextAuthenticationFilter`, leaves the key unbound and reports one `INVALID_VALUE` with boundary `INTERNAL_CALL` and no destination
      (DIAG-08). The request continues;
    - `DIAG-09 an invalid claim is reported without the value or the codec's message`;
    - `IN-06 a claim value longer than max-value-length is bound`, a 300-character string claim. This is a guard: show it red with
      `ContextAuthenticationFilter` temporarily dropping contributed values longer than `wire.max-value-length`, then revert
      ([plan.md](plan.md#guard-tests));
    - `IN-06 any other exception from the claim's codec propagates unchanged`: a codec whose `decode` throws `IllegalStateException`;
      `contribute` throws the same instance and `invalid(key)` is not called. This is a guard: show it red with the catch temporarily widened
      to `RuntimeException`, then revert ([plan.md](plan.md#guard-tests)).
  - Green: the `String` check and the `IllegalArgumentException` catch (and only it) in `JwtClaimContributor`, both calling `invalid(key)`.
  - Completes: IN-06 (invalid claims, no length check, other codec exceptions); DIAG-08; DIAG-09 (claims).
  - Verify: Unit `JwtClaimContributorTest`.

**Checkpoint**: IT-2.1 and IT-2.5 – IT-2.8 green on JDK 21 and JDK 25; with Phase 4, IT-2.1 – IT-2.9.

---

## Phase 6: Close

- [ ] T029 Verify each requirement one by one against the code and its tests, using the coverage table in
  [plan.md](plan.md#requirement-coverage):
  - Requirements: CORE-13, POL-07, POL-08, POL-10, DEST-01 – DEST-04, WIRE-01, WIRE-02, WIRE-04 – WIRE-08, IN-01 – IN-08, OUT-01 – OUT-04,
    DIAG-01 – DIAG-03, DIAG-08, DIAG-09 and TEST-01.
  - SC-001: TrustBoundaryIT has no `@Disabled` left, and IT-2.1 – IT-2.9 pass in `integrationTestJdk21` and `integrationTestJdk25`.
  - SC-002: no scenario's partner observation holds a context header.
  - SC-003: no untrusted, repeated, invalid or oversized header fails a request (T018, T019, T023, T024, IT-2.5, IT-2.8).
  - Every requirement has a test whose display name starts with its ID.
  - `boundrail-context-core` still has no runtime dependencies
    (`./gradlew :boundrail-context-core:dependencies --configuration runtimeClasspath`).
  - `-spring` declares Security, Micrometer, servlet, web and restclient only as `compileOnly`
    (`./gradlew :boundrail-context-spring:dependencies --configuration runtimeClasspath`).
  - `-test` declares `-spring` as `api`, and `spring-web` and `jakarta.servlet-api` only as `compileOnly` (TEST-01; BuildSmokeIT, T020a).
  - By inspection: no required-key check runs at an in-process boundary (POL-08); no log statement in `-spring` passes a value or an exception
    message (DIAG-09); `Metrics.globalRegistry` is not referenced (DIAG-03); no adapter catches anything wider than `IllegalArgumentException`
    around a codec call (WIRE-05).

  Run `./gradlew check` fresh, which covers JDK 21, JDK 25 and the forced ThreadLocal run. With the CI run green, this closes the feature.

## Dependencies & Execution Order

- Phase 2: T001 → T002 → T003. T004 and T005 are independent of T001 and of each other.
- Phase 3: T006 needs nothing from Phase 2. T006a needs T003 and T006. T007 needs T004, T005 and T006a.
- US1:
  - T008 needs T006. T009 needs T003 and T008. T010 needs T007 and T009. T011 needs T007 and T010. T012 needs T011.
  - T013 needs T010.
  - T014 needs T005, T007 and T013. T015 needs T004, T005, T007 and T013. T015a needs T015 and the spec.md update.
- US2:
  - T016 needs T005 and T006. T017 needs T016. T018 needs T007, T017 and the spec.md update. T019 needs T007, T016 and the spec.md IN-02 update.
  - T020 needs T013 and T019. T020a needs T020.
  - T021 needs T016. T022 needs T021.
  - T023 needs T007 and T016. T024 needs T020, T023 and the spec.md IN-02 update.
  - T025 needs T016. T026 needs T007 and T025.
  - T027 needs T020 and T026; IT-2.1 also needs US1's outbound path (T010).
  - T028 needs T026 and T027.
- T029 comes last.
- US1 (T008 – T015a) and the first US2 tasks (T016 – T019, T021 – T023, T025, T026) can be interleaved after Phase 3. Tasks touching the same file
  run in sequence.
- Every task is committed and reviewed on its own, on branch `003-http-trust`.

## Exit Checklist

- [ ] Scenarios enabled and green on JDK 21 and JDK 25: IT-2.1, IT-2.2, IT-2.3, IT-2.4, IT-2.5, IT-2.6, IT-2.7, IT-2.8, IT-2.9
- [ ] Requirements implemented with tests named after their IDs (roadmap traceability row M2): CORE-13, POL-07 – POL-08, POL-10,
  DEST-01 – DEST-04, WIRE-01 – WIRE-02, WIRE-04 – WIRE-08, IN-01 – IN-08, OUT-01 – OUT-04, DIAG-01 – DIAG-03, DIAG-08 – DIAG-09, TEST-01
- [ ] Guard tests (IT-2.2, IT-2.3, IT-2.6, T020a's added IT-2.2 and IT-2.3 assertions, the T002, T005, T015, T017, T019 and T020a guards
  (T020a's in BuildSmokeIT), the two unit guards each in T001, T003, T006a, T007, T018, T024 and T028, the three unit guards each in T010, T011
  and T015a, the four unit guards in T012, and the six unit guards in T022)
  shown red against their temporary change, and the change reverted
- [ ] `boundrail-context-core` still has no runtime dependencies; optional libraries are `compileOnly` in `-spring`
- [ ] `./gradlew check` green
