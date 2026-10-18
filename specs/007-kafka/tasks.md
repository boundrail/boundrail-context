---
description: "Task list for 007-kafka"
---

# Tasks: Kafka

**Input**: Design documents from `/specs/007-kafka/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [contracts/kafka-api.md](contracts/kafka-api.md); 006-identity merged
(d6243fc), `main` at d6243fc

**Tests**: Required (constitution principle VII). Each task is one red-green cycle: its failing test, with the behaviour it pins down, and the
minimum code that makes it pass, committed and reviewed together. Every task leaves `./gradlew check` green. Every test's display name starts
with the requirement or scenario ID it covers.

- Approved by the user on 2026-10-18: these tasks, the plan, the TDD exception for the KAF-06 Javadoc (T013), and the guard in T010
  ([plan.md](plan.md#tdd-exceptions), [plan.md](plan.md#guard-tests)).
- Q5 – Q7 are settled (D78); Q1 – Q4 are open ([plan.md](plan.md#spec-gaps)) and expected to be settled on T001's outcome. Each blocks
  the tasks it names.
- T001, the spike, runs first; its outcome goes back to the user before any implementation task. Where the user picks another option than
  the plan's recommendation, the planner rewrites the affected tasks before they start.
- A test that is green before its task is accepted as a guard when its red is shown against a named temporary change to the finished code,
  which is then reverted; the leader reports it in the task report, the planner adds it to plan.md's Guard Tests table, and the reviewer
  checks it. A negative assertion in the same test as a positive assertion that is red before the task is not a guard.
- Review follow-ups get a letter suffix (T004a, …).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: The user story the task belongs to (US1: context travels with messages under detached semantics; US2: header trust is
  configurable; US3: manual scoping for batch listeners)

Path abbreviations:

| Short | Path |
|---|---|
| `spring/` | `boundrail-context-spring/src/main/java/io/github/boundrail/context/spring/` |
| `springtest/` | `boundrail-context-spring/src/test/java/io/github/boundrail/context/spring/` |
| `kafka/` | `boundrail-context-kafka/src/main/java/io/github/boundrail/context/kafka/` |
| `kafkatest/` | `boundrail-context-kafka/src/test/java/io/github/boundrail/context/kafka/` |
| `it/` | `integration-tests/src/test/java/io/github/boundrail/context/it/` |
| `topology/` | `integration-tests/src/test/java/io/github/boundrail/context/it/topology/` |

Verify commands:

- **Unit**: `./gradlew :<project>:test --tests '<TestClass>'` for red and green, then `./gradlew check` before commit.
- **IT**: the scenario run, then `./gradlew check` before commit:

  ```bash
  ./gradlew --continue \
            :integration-tests:integrationTestJdk21 --tests '*DeadlinePropagationIT' --tests '*AuthorityPropagationIT' \
            :integration-tests:integrationTestJdk25 --tests '*DeadlinePropagationIT' --tests '*AuthorityPropagationIT'
  ```

- **IT+**: the IT run plus `--tests '*ContextPropagationIT'`, `--tests '*TrustBoundaryIT'` and `--tests '*TopologySmokeIT'` on both tasks,
  for tasks that change shared topology code.

Enabling a scenario means: remove `@Disabled`; keep its `@DisplayName`; replace the comment with the spec's current scenario text and
requirement IDs; write the body. Scenarios use a marker and assert deltas, never totals, because the topology is shared. "Alice's token" is a
fresh `topology.jwts().alice()` per scenario.

"Lazy initialization" in a test means the setup first used in 003's T006a (`spring.main.lazy-initialization=true` and a
`LazyInitializationBeanFactoryPostProcessor`). "The test policy" is `kafkatest/support/TestPolicies.java` (T004): `tenant` (string codec,
`Tenant-Id`, propagates to `MESSAGE` and `INTERNAL_CALL`), `order-count` (long codec, `Order-Count`, `MESSAGE`) and `priority` (long codec,
`Priority`, `INTERNAL_CALL` only), with variants that make keys required or use a counting or throwing codec. "The broker" is
`kafkatest/support/KafkaBroker.java` (T006); every broker test uses its own topic and group id.

## Phase 0: Spike

- [ ] T001 Spike: the producer hook (D71), the listener advice chain (D72) and an around-style hook for error handling (D73).
  - On a scratch project with a Testcontainers broker, run the five items of [plan.md](plan.md#what-t001-confirms): the
    `ProducerPostProcessor` on a template bean's `ProducerFactory` (exceptions from every send method, `executeInTransaction`, observation,
    composition with the application's template interceptor, what else it covers); the advice prepended to Boot's and an application's factory
    (containers from `@KafkaListener` and `createContainer`, the arguments and `getThis()` the advice sees, application advice inside,
    `RecordInterceptor` outside, a `ContainerCustomizer` replacing the chain, the ScopedValue backend on JDK 25 from the packaged JAR); the
    `CommonErrorHandler` decorator installed per container before start (`DeadLetterPublishingRecoverer` in `fail` mode, restore, late
    containers, deserialization failures, decode exceptions, transactional containers); the backend `-kafka`'s unit tests load; whether billing
    needs a `NewTopic`.
  - Outcome into plan.md (Spike), with a recommendation for Q1 – Q4. No code kept, nothing committed. The outcome goes to the user, who
    settles Q1 – Q7 before T002 starts.
  - Verify: the outcome section in plan.md, and the user's decisions recorded in plan.md's Status and Spec Gaps.

---

## Phase 1: Setup

None as separate tasks. 001 built the `-kafka` project, the Testcontainers broker in the topology and the disabled scenarios. The build changes
are part of the red step of the task whose tests need them: `-kafka`'s dependencies in T003 and T006, the catalog alias `spring-boot-kafka` in
T006, and `integration-tests`' `spring-boot-kafka` in T010.

---

## Phase 2: Foundational

- [ ] T002 `AdapterSupport` in `io.github.boundrail.context.spring.internal` (D70).
  - Red, in `springtest/internal/AdapterSupportTest.java` (`ApplicationContextRunner` with `BoundrailContextAutoConfiguration`, a
    `SimpleMeterRegistry`, a recording `ViolationListener`, `OutputCaptureExtension`):
    - `POL-07 AdapterSupport gives the effective policy, merged with every ContextPolicy bean`: `policy().keys()` holds the built-in keys and
      a `tenant` key from a `ContextPolicy` bean;
    - `DIAG-03 a violation reported through AdapterSupport is logged and counted by the context's reporter and passed to its ViolationListener
      beans`: one `INVALID_VALUE` for `tenant` with boundary `MESSAGE`: `boundrail.context.violations{kind=invalid_value,key=tenant,
      boundary=message}` is 1, the WARN line names the key, wire name and boundary, the listener receives the same instance (DIAG-11);
    - `DIAG-04 a violation built by AdapterSupport carries the current thread's name and no destination`;
    - `DIAG-02 AdapterSupport gives violations.mode and wire.max-value-length as bound`: defaults `fail` and 256; with `report` and 10,
      `false` and 10.
  - Green: the public interface `spring/internal/AdapterSupport.java`; the `boundrailAdapterSupport` bean in
    `spring/BoundrailContextAutoConfiguration.java`, delegating to `EffectivePolicy`, `ViolationReporter` and the properties
    ([plan.md](plan.md#spring-side-d70)). Name per Q6.
  - Completes: D70's Spring side (the reporting KAF-01 – KAF-05 use; no requirement on its own).
  - Verify: Unit `AdapterSupportTest` in `:boundrail-context-spring`.
- [ ] T003 The Kafka auto-configuration, `BoundrailKafkaProperties` and the `KafkaContext` bean.
  - Red:
    - in `boundrail-context-kafka/build.gradle.kts`: `api(project(":boundrail-context-spring"))`, `implementation(libs.spring.boot.autoconfigure)`
      and `testImplementation(libs.spring.boot.starter.test)`;
    - in `kafkatest/KafkaAutoConfigurationTest.java` (`ApplicationContextRunner` with both libraries' auto-configurations):
      - `KAF-05 the Kafka auto-configuration provides one KafkaContext bean, and KafkaContext has no public constructor`;
      - `KAF-05 the Kafka auto-configuration is registered in AutoConfiguration.imports and ordered after the Spring module's and Spring Boot's
        Kafka auto-configuration`: `ImportCandidates.load(AutoConfiguration.class, …)` holds it; its `@AutoConfiguration` names
        `BoundrailContextAutoConfiguration` in `after` and `org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration` in
        `afterName`;
      - per Q5: `KAF-05 without the Spring module's auto-configuration, or without Spring Kafka, no Kafka bean is created and the context
        starts`: `BoundrailContextAutoConfiguration` left out; `FilteredClassLoader(KafkaTemplate.class)`; a context with both as the red
        control (one test);
      - `KAF-04 kafka.trust-headers defaults to true and is bound with Boot's boolean binding` (parametrized, read from the
        `BoundrailKafkaProperties` bean): unset → `true`; `true`, `on`, `YES`, `1` → `true`; `false`, `Off`, `no`, `0` → `false`;
      - `KAF-04 a kafka.trust-headers that Boot cannot convert to a boolean fails startup, also with lazy initialization`: `maybe` and `2`,
        with and without lazy initialization.
  - Green: `kafka/BoundrailKafkaAutoConfiguration.java`, `kafka/BoundrailKafkaProperties.java` (name per Q6), `kafka/KafkaContext.java` (a
    package-private constructor taking `AdapterSupport` and the trust setting; no method yet), the `@Lazy(false)` `boundrailKafkaContext` bean,
    and `boundrail-context-kafka/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
    ([plan.md](plan.md#kafka-auto-configuration-and-properties)).
  - Completes: KAF-04 (binding, startup failure); KAF-05 (the bean, no public constructor); the Configuration section's `kafka.trust-headers`
    failure; D77's activation conditions (Q5).
  - Verify: Unit `KafkaAutoConfigurationTest` in `:boundrail-context-kafka`.

**Checkpoint**: the Kafka auto-configuration starts and binds its property; nothing reads records yet.

---

## Phase 3: User Story 1 - Context travels with messages under detached semantics (Priority: P1) 🎯 MVP

**Goal**: Records sent during a request carry the `MESSAGE` keys; each record-mode listener invocation runs in exactly its record's snapshot,
without the deadline or the authority.

**Independent Test**: IT-3.8 and IT-4.9 in the IT command.

- [ ] T004 [US1] `KafkaContext.forRecord`: a trusted record's snapshot (KAF-02).
  - Red:
    - test support: `kafkatest/support/TestPolicies.java` (the test policy and its variants);
    - in `kafkatest/RecordSnapshotTest.java` (`ApplicationContextRunner` with both auto-configurations, the test policy, a `SimpleMeterRegistry`
      and a recording `ViolationListener`; `ConsumerRecord` values with `RecordHeaders` built directly):
      - `KAF-02 a trusted record's snapshot holds exactly the MESSAGE keys decoded from their wire headers`: `Tenant-Id` `Zürich` (UTF-8),
        `Order-Count` `7`, `Boundrail-Initiator` `user:alice` → exactly `tenant`, `order-count` and `boundrail.initiator`, the initiator
        through `Initiator.of`;
      - `KAF-02 headers of keys that do not propagate to MESSAGE, Boundrail-Deadline-Ms, Authorization and other names are ignored and not
        reported, and wire names are compared exactly` (with `Tenant-Id: acme` bound in the same record): `Priority: 5`,
        `Boundrail-Deadline-Ms: 5000`, `Authorization: Bearer x`, `X-Other`, and `tenant-id: evil` (not a repeat of `Tenant-Id`) give a
        snapshot of `tenant=acme` only, no `DEADLINE` or `AUTHORITY` (ID-02), and no violation;
      - `KAF-02 a record without context headers gives the empty snapshot`;
      - `KAF-02 a decode failure leaves the key unbound and reports one INVALID_VALUE with boundary MESSAGE and no destination, and an empty
        Boundrail-Initiator is a decode failure` (WIRE-05, WIRE-02, DIAG-08): `Order-Count: abc`; `Boundrail-Initiator` with zero bytes; the
        other keys still bound;
      - `KAF-02 an exception other than IllegalArgumentException from decode propagates unchanged and nothing is reported`: a codec throwing
        `IllegalStateException` (the same instance reaches the caller);
      - `KAF-02 violations are reported through the reporting of the context that owns the KafkaContext, and never contain the value`
        (DIAG-03, DIAG-09, DIAG-11): the counter in that context's registry, the listener of that context, and neither the `Violation` nor the
        captured log contains `abc`;
      - `KAF-05 forRecord does not enter the snapshot, and each call reports the record's violations again`: inside an outer binding of
        `tenant=outer`, `Context.capture()` is unchanged after the call; two calls with `Order-Count: abc` give two `INVALID_VALUE`;
      - `CORE-17 forRecord(null) throws NullPointerException`.
  - Green: in `kafka/KafkaContext.java`, `forRecord` and the package-private `snapshot(record)`: the trusted loop with single-header lookup,
    UTF-8 string, `Initiator.of` or codec, `IllegalArgumentException` → `INVALID_VALUE`, and the snapshot built as in
    [plan.md](plan.md#record-snapshot-kaf-02-kaf-04-kaf-05); no shape checks yet (T005), no trust branch yet (T012). Per Q7.
  - Completes: KAF-02 (trusted decoding, ignored headers, exact names, empty record, decode failures, other exceptions, reporting, DIAG-09);
    KAF-05 (`forRecord` without entering, reporting per call); CORE-17.
  - Verify: Unit `RecordSnapshotTest` in `:boundrail-context-kafka`.
- [ ] T005 [US1] Repeated, `null`, oversized and malformed header values (KAF-02, D74).
  - Red, in `kafkatest/RecordSnapshotTest.java`:
    - `KAF-02 a wire header carried more than once leaves the key unbound, decodes none of its values and reports one INVALID_VALUE`: two and
      three `Tenant-Id` headers, a counting codec at zero calls, one violation each time; other keys still bound;
    - `KAF-02 a header with a null value leaves the key unbound and reports one INVALID_VALUE`: `Tenant-Id` and `Boundrail-Initiator`;
    - `KAF-02 WIRE-04 applies first, to the value decoded as UTF-8 with replacement in UTF-16 code units: a longer value is unbound with one
      OVERSIZED_VALUE, also when it is malformed` (parametrized): 256 `a` bound, 257 not; 256 `é` (512 bytes) bound; 128 U+1F600 (256 code
      units) bound, 129 not; 300 bytes of `0x80` → one `OVERSIZED_VALUE` and no `INVALID_VALUE`; with `wire.max-value-length=10`, 11 `a` not
      bound;
    - `KAF-02 a value that is not well-formed UTF-8 leaves the key unbound, reports one INVALID_VALUE and is not decoded` (parametrized):
      `0x80`, `C0 80` (overlong), `ED A0 80` (surrogate), `E2 82` (truncated); a counting codec at zero calls.
  - Green: in `kafka/KafkaContext.java`'s trusted loop, the checks in the order of [plan.md](plan.md#record-snapshot-kaf-02-kaf-04-kaf-05):
    count, `null`, length on the replaced string, strict UTF-8 decode.
  - Completes: KAF-02 (repeat, `null`, WIRE-04, malformed UTF-8).
  - Verify: Unit `RecordSnapshotTest`.
- [ ] T006 [P] [US1] The producer hook writes the `MESSAGE` headers (KAF-01, D71; mechanism per Q1).
  - Red:
    - `spring-boot-kafka = { module = "org.springframework.boot:spring-boot-kafka" }` in `gradle/libs.versions.toml` (no version), and
      `testImplementation(libs.spring.boot.kafka)` and `testImplementation(libs.testcontainers.kafka)` in
      `boundrail-context-kafka/build.gradle.kts`;
    - test support: `kafkatest/support/KafkaBroker.java` (one `apache/kafka:4.2.1` container per test JVM, started on first use; reading a
      topic back with a real `KafkaConsumer` and a timeout);
    - in `kafkatest/ProducerHeadersTest.java` (`ApplicationContextRunner` with Boot's `KafkaAutoConfiguration` and both libraries'
      auto-configurations, `spring.kafka.bootstrap-servers` of the broker, the test policy with no required key):
      - `KAF-01 a record sent through the KafkaTemplate bean carries a header per bound MESSAGE key, holding the UTF-8 bytes of its encoding`:
        `tenant` `Zürich`, `order-count` 7 and `user:alice` bound → `Tenant-Id`, `Order-Count`, `Boundrail-Initiator` with those bytes;
        `priority` bound and no `Priority` header; a `tenant` of `a\r\nb` written as is (D74);
      - `KAF-01 no Authorization or Boundrail-Deadline-Ms header is written, and an expired deadline neither fails nor changes the send`
        (ID-06, D75): with an authority and a live deadline bound, the record has `Tenant-Id` and neither header; with an expired deadline,
        the send succeeds with the same headers and no violation is reported;
      - `KAF-01 every send method of a KafkaTemplate bean is covered, including Message sends and sends inside executeInTransaction`
        (parametrized): `send(topic, value)`, `send(topic, key, value)`, `send(topic, partition, key, value)`, `send(topic, partition,
        timestamp, key, value)`, `send(ProducerRecord)`, `send(Message<?>)`, `sendDefault(value)`; and `executeInTransaction(ops ->
        ops.send(…))` on a template with `spring.kafka.producer.transaction-id-prefix`;
      - `KAF-01 a KafkaTemplate subclass bean, and a second template bean sharing the producer factory, are covered`;
      - `KAF-01 an application's own template interceptor still runs, and the record still carries the headers` (D71): an interceptor set in
        the template's `@Bean` method records its calls;
      - `KAF-01 headers come from the sending thread's context at the send call`: one send inside a `tenant=a` scope, one outside → `a`, then
        no `Tenant-Id`;
      - `KAF-01 an exception from encode propagates unchanged from the send call, nothing is reported, and the record is not sent` (WIRE-05):
        a throwing codec; the topic holds nothing.
  - Green: the hook per Q1 (recommended: `kafka/TemplatePostProcessor.java`, `kafka/ContextProducerPostProcessor.java`,
    `kafka/ContextProducer.java`), registered as a `static` bean in `kafka/BoundrailKafkaAutoConfiguration.java`; header writing from the
    snapshot filtered for `MESSAGE` ([plan.md](plan.md#producer-kaf-01-d71)).
  - Completes: KAF-01 (headers, UTF-8, CR/LF, no `Authorization` or `Boundrail-Deadline-Ms`, expired deadline, every send method,
    `executeInTransaction`, subclass, composition, sending thread, encode exceptions).
  - Verify: Unit `ProducerHeadersTest` in `:boundrail-context-kafka`.
- [ ] T007 [US1] Application-set headers and WIRE-04 at send (KAF-01, D74).
  - Red, in `kafkatest/ProducerHeadersTest.java`:
    - `KAF-01 a header application code set with a key's wire name is left unchanged and that key is not encoded`: a record carrying
      `Tenant-Id: app` keeps exactly that one header, with a counting codec at zero `encode` calls; a `Tenant-Id` with a `null` value is
      kept as is; `Authorization: Bearer app` is kept; `tenant-id: app` is another name, so `Tenant-Id` is written too (WIRE-06); no
      violation;
    - `KAF-01 an encoding longer than wire.max-value-length is not written, one OVERSIZED_VALUE with boundary MESSAGE and no destination is
      reported, and the record is sent`: 257 `a` (not written, other headers written), 256 `a` and 256 `é` (written).
  - Green: the "already carries" check and the length check in `kafka/ContextProducer.java`.
  - Completes: KAF-01 (application-set headers, WIRE-04).
  - Verify: Unit `ProducerHeadersTest`.
- [ ] T008 [US1] The required-key check at send (KAF-01, POL-08, DIAG-02).
  - Red, in `kafkatest/ProducerRequiredKeysTest.java` (the test policy with `tenant` and `order-count` required, and `priority` required but
    not propagating to `MESSAGE`):
    - `KAF-01 in fail mode each unbound required MESSAGE key is reported once as MISSING_REQUIRED in key-name order, then
      ContextViolationException is thrown from the send call and the record never reaches the producer`: both unbound → violations for
      `order-count`, then `tenant`, boundary `MESSAGE`, no destination; the exception carries the first; the topic holds nothing; `priority`
      is not reported;
    - `KAF-01 in report mode the record is sent without the key and with the other headers`;
    - `KAF-01 an application-set wire header does not satisfy the required-key check`: `Tenant-Id: app` on the record, `tenant` unbound →
      `MISSING_REQUIRED` and, in `fail` mode, the exception;
    - `KAF-01 inside executeInTransaction the ContextViolationException propagates from the send, and nothing is committed`: a
      `read_committed` consumer reads nothing.
  - Green: the check at the start of `send` in `kafka/ContextProducer.java`.
  - Completes: KAF-01 (required keys, both modes, POL-08, transactions).
  - Verify: Unit `ProducerRequiredKeysTest`.
- [ ] T009 [US1] The listener scope advice (KAF-02, KAF-03).
  - Red, in `kafkatest/ListenerScopeAdviceTest.java` (the advice built with the `KafkaContext` of an `ApplicationContextRunner` context with
    the test policy, applied through a `ProxyFactory` around a hand-written `MessageListener` that records `Context.capture()`, as
    `ContainerProperties` applies the advice chain; records built directly):
    - `KAF-02 a record listener runs in exactly its record's snapshot, replacing the current one, which is restored afterwards`: outer
      binding `tenant=outer`, `priority=1`; record `Tenant-Id: acme` → the listener sees `tenant=acme` and no `priority`; afterwards the outer
      snapshot is current again;
    - `KAF-02 a record without context headers runs in the empty snapshot, replacing the current one` (D73);
    - `KAF-02 consecutive records on one thread see only their own bindings`: `acme`, then a record with no headers, then `beta`;
    - `KAF-02 an exception the listener throws propagates unchanged and the previous snapshot is restored` (CORE-08): a `RuntimeException`
      and an `Error`, the same instances;
    - `KAF-02 an exception other than IllegalArgumentException from decode propagates as the listener's failure, the listener is not
      invoked, and nothing is reported`;
    - `KAF-02 each delivery builds its snapshot again and reports its violations again`: the same record with two `Tenant-Id` headers
      delivered twice → two `INVALID_VALUE`;
    - `KAF-05 a BatchMessageListener is not scoped: it runs in the current snapshot, and nothing is decoded or reported for its records`;
    - `KAF-03 listener methods other than onMessage with a record pass through unscoped`: `onPartitionsAssigned` of a `ConsumerSeekAware`
      listener runs in the outer snapshot.
  - Green: `kafka/ListenerScopeAdvice.java` ([plan.md](plan.md#listener-scope-kaf-02-kaf-03-d72)): `ContextSnapshot.EMPTY.call(() ->
    binding.call(invocation::proceed))` for a record `onMessage`, pass-through otherwise. Not yet added to any factory (T010).
  - Completes: KAF-02 (invocation: replace, restore, empty, consecutive, exceptions, redelivery); KAF-03 (around-style scope); KAF-05 (a batch
    listener passes through).
  - Verify: Unit `ListenerScopeAdviceTest`.
- [ ] T010 [US1] The advice on every listener container factory bean, outermost; enable IT-3.8 and IT-4.9.
  - Red:
    - in `kafkatest/ListenerScopeTest.java` (the broker; Boot's `KafkaAutoConfiguration` and both libraries' auto-configurations; records
      produced by a plain `KafkaProducer` with exact headers):
      - `KAF-03 the library's advice is first in the advice chain of Boot's listener container factory and of every
        AbstractKafkaListenerContainerFactory bean, ahead of the application's advice, and only once` (no broker needed): Boot's factory and
        an application `ConcurrentKafkaListenerContainerFactory` bean whose `@Bean` method sets its own advice;
      - `KAF-03 a @KafkaListener on Boot's factory or on an application factory bean, and a container bean created with
        factory.createContainer, run in their record's snapshot, with the application's advice inside it` (one row each): the listener and the
        application advice record `tenant=acme` for a record with `Tenant-Id: acme`;
      - `KAF-02 a listener's violations are reported through the reporting of the context that owns its container`: a record with two
        `Tenant-Id` headers → that context's counter `boundrail.context.violations{kind=invalid_value,key=tenant,boundary=message}` rises by
        one;
      - `KAF-01 a record a listener sends carries the consumed record's MESSAGE keys`: the listener sends through the template bean; the
        record read back has `Tenant-Id: acme`;
      - guard: `KAF-05 a batch @KafkaListener is not scoped, and nothing is decoded or reported for its records`: a factory with
        `setBatchListener(true)`; the listener sees no `tenant` and a repeated header reports nothing. Show it red with
        `ListenerScopeAdvice` temporarily scoping a `List` argument in its first record's snapshot, then revert
        ([plan.md](plan.md#guard-tests));
    - `testImplementation(libs.spring.boot.kafka)` in `integration-tests/build.gradle.kts`;
    - enable IT-3.8 in `it/DeadlinePropagationIT.java`: alice's request to this class's edge `POST /kafka/publish` with a marker returns 200;
      billing's observation for the marker appears within 30 seconds, and after 2 more seconds it is still exactly one; it has no
      `ctx.deadline-remaining-nanos`, `ctx.tenant=acme`, `ctx.initiator=user:alice`, `kafka.delay-millis` of at least 4000, and its
      `kafka.header-names` lack `Boundrail-Deadline-Ms`; comment *(KAF-01, KAF-02, KAF-03, KAF-04)*;
    - enable IT-4.9 in `it/AuthorityPropagationIT.java`: alice's request to the shared edge `POST /kafka/publish` with a marker; billing's one
      observation has `ctx.initiator=user:alice` and no `ctx.authority-subject`, and its `kafka.header-names` contain `Boundrail-Initiator`
      and not `Authorization`; comment *(ID-06, KAF-01, KAF-02, KAF-04)*.
  - Green:
    - `kafka/ListenerContainerFactoryPostProcessor.java`, a `static` bean in `kafka/BoundrailKafkaAutoConfiguration.java`
      ([plan.md](plan.md#listener-scope-kaf-02-kaf-03-d72));
    - topology test code ([plan.md](plan.md#integration-tests)): `spring.kafka.bootstrap-servers` for every service and billing's group id
      and offset reset in `topology/Topology.java`; `POST /kafka/publish` in `topology/EdgeApp.java`; the record-mode `@KafkaListener` with
      the 4-second wait (and `NewTopic` if T001 needs it) in `topology/BillingApp.java`; `consumed(record)` and the `kafka.*` entries in
      `topology/ObservedContext.java`.
  - Completes: KAF-03 (every factory bean, Boot's, `createContainer` bean, outermost, composition; the ScopedValue backend through IT-3.8 on
    `integrationTestJdk25`); KAF-02 (the container's context reports); KAF-01 (a listener's send); KAF-05 (batch `@KafkaListener`, guard);
    IT-3.8; IT-4.9 (ID-06's `MESSAGE` clause).
  - Verify: Unit `ListenerScopeTest`; IT+.
- [ ] T011 [US1] The container's error handling runs inside the record's snapshot (KAF-03, D73; mechanism per Q3 and Q4).
  - Red, in `kafkatest/ListenerErrorHandlingTest.java` (the broker; the test policy with `tenant` required, `violations.mode=fail`):
    - `KAF-03 a CommonErrorHandler handling a record whose listener threw runs in that record's snapshot, and the previous snapshot is
      restored afterwards`: an application handler recording `Context.capture()` in `handleOne` and in `handleRemaining`
      (`seeksAfterHandling` false and true) sees `tenant=acme`; the consumer thread has no binding afterwards; the application's handler,
      a `CommonErrorHandler` bean on Boot's factory and one set on its own factory, still runs (composition);
    - `KAF-03 a DeadLetterPublishingRecoverer publishes the failed record in fail mode with a required MESSAGE key`:
      `DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), new FixedBackOff(0, 0))`; the `.DLT` topic receives the record and
      no `MISSING_REQUIRED` is counted;
    - per Q4: `KAF-03 the error handler also runs in the record's snapshot for a deserialization failure, and in the empty snapshot when
      building the snapshot threw`, and no violation is reported twice for one delivery.
  - Green: the error-handling hook per Q3 (recommended: a `CommonErrorHandler` decorator installed per container before start), in
    `kafka/` and `kafka/BoundrailKafkaAutoConfiguration.java`. If the user chooses the D73 fallback, this task is dropped and T013
    documents that error handling runs outside the scope, after the spec is revised.
  - Completes: KAF-03 (error handling inside the record's snapshot).
  - Verify: Unit `ListenerErrorHandlingTest`.

**Checkpoint**: IT-3.8 and IT-4.9 green on JDK 21 and JDK 25; every acceptance suite fully green.

---

## Phase 4: User Story 2 - Header trust is configurable (Priority: P2)

**Goal**: With `kafka.trust-headers=false` nothing is bound from record headers, and each policy wire header present is reported.

**Independent Test**: Unit tests whose display names start with `KAF-04`.

- [ ] T012 [US2] Untrusted record headers (KAF-04).
  - Red, in `kafkatest/RecordTrustTest.java` (as `RecordSnapshotTest`, with `boundrail.context.kafka.trust-headers=false` unless stated):
    - `KAF-04 with trust-headers=false the snapshot is empty and one UNTRUSTED_HEADER is reported per policy wire header name present, however
      often and whatever its value`: `Tenant-Id` twice, `Boundrail-Initiator` with a `null` value, `Boundrail-Deadline-Ms: 5000`, `Priority: 5`
      (no `MESSAGE`), an oversized `Order-Count`, plus `Authorization`, `X-Other` and `tenant-id` → exactly five `UNTRUSTED_HEADER`
      violations, one each for `tenant`, `boundrail.initiator`, `boundrail.deadline`, `priority` and `order-count`, each with boundary
      `MESSAGE` and no destination; none for the other names; no `INVALID_VALUE` or `OVERSIZED_VALUE`; a counting codec at zero `decode`
      calls; `AUTHORITY` and `DEADLINE` unbound (ID-02);
    - `KAF-04 trust-headers=off disables trust and the default trusts`: the same `Tenant-Id: acme` record gives an empty snapshot with `off`
      and `tenant=acme` with the property unset.
  - Green: the trust branch in `kafka/KafkaContext.java`'s `snapshot` ([plan.md](plan.md#record-snapshot-kaf-02-kaf-04-kaf-05)).
  - Completes: KAF-04 (untrusted path, policy wire header names, `AUTHORITY` and `DEADLINE` never bound regardless of configuration).
  - Verify: Unit `RecordTrustTest`.

The default (`true`) is also exercised by IT-3.8 and IT-4.9 (T010).

---

## Phase 5: User Story 3 - Manual scoping for batch listeners (Priority: P3)

Delivered inside the tasks above: the `KafkaContext` bean in T003; `forRecord` (without entering, reporting per call, CORE-17) in T004 and
its trust setting in T012; the advice passing batch listeners through in T009; a batch `@KafkaListener` against the broker in T010 (guard).

---

## Phase 6: Documentation and close

- [ ] T013 The `KafkaContext` Javadoc (KAF-06). [TDD-EXCEPTION: review of T013, and inspection in T014 against KAF-06's wording]
  - In `kafka/KafkaContext.java`, the class Javadoc states: batch listeners are not scoped and `forRecord` gives each record's snapshot
    (KAF-05); which sends are covered and which are not (KAF-01, worded as Q1 settles); which listener containers are covered, and that
    `RecordInterceptor`s and replaced advice chains are outside (KAF-03, as Q2 settles); the deadline is neither sent nor applied when a
    record is sent (KAF-01, D75); only the listener invocation and the container's error handling are scoped, so code that completes a
    `CompletableFuture` or `Mono` a listener returns runs with its own thread's context (KAF-03, as Q3 settles; with the D73 fallback, that
    error handling runs outside the scope).
  - No test: documentation, approved as a TDD exception ([plan.md](plan.md#tdd-exceptions)).
  - Completes: KAF-06.
  - Verify: `./gradlew :boundrail-context-kafka:javadoc` reports no warning for `KafkaContext`; then `./gradlew check`.
- [ ] T014 Verify each requirement one by one against the code and its tests, using the coverage table in
  [plan.md](plan.md#requirement-coverage):
  - Requirements: CORE-17, KAF-01 – KAF-06, as revised by the user's answers to Q1 – Q7.
  - SC-001: no `@Disabled` is left in any acceptance suite; IT-3.8 and IT-4.9 pass in `integrationTestJdk21` and `integrationTestJdk25`.
  - SC-002: IT-3.8 passes in `integrationTestJdk25` (ScopedValue backend); by inspection, only `ContextSnapshot.call` scopes a listener,
    and no `RecordInterceptor` or other set/restore hook is used for scoping.
  - SC-003: IT-3.8 and IT-4.9 assert no `Boundrail-Deadline-Ms` and no `Authorization` header; T006's unit test covers the producer.
  - Every requirement has a test whose display name starts with its ID, except KAF-06: check the Javadoc of `KafkaContext` against KAF-06,
    word for word in meaning.
  - The Configuration section's startup failure has a test with lazy initialization (T003).
  - `boundrail-context-core` still has no runtime dependencies (`./gradlew :boundrail-context-core:dependencies --configuration
    runtimeClasspath`); `boundrail-context-spring` has no Kafka dependency (`./gradlew :boundrail-context-spring:dependencies --configuration
    runtimeClasspath`); only `-kafka` names a Kafka type.
  - By inspection: no reflection on Spring Kafka internals (D71, D72); nothing new is static (RT-09); violations carry no value; the
    `AdapterSupport` type is in `spring.internal` and no contract names it.
  - spec.md's Status, roadmap.md's M6 and the README stay as they are, as for 002 – 006.

  Run `./gradlew check` fresh, which covers JDK 21, JDK 25 and the forced ThreadLocal run. With the CI run green, this closes the feature.

## Dependencies & Execution Order

- T001 first; its outcome and the user's answers to Q1 – Q7 come before T002 ([plan.md](plan.md#spec-gaps)).
- Foundational: T003 needs T002 (the `AdapterSupport` bean).
- Record snapshot: T004 needs T003 and Q7. T005 needs T004 (same file).
- Producer: T006 needs T003 and Q1, and is independent of T004 and T005 (different files, hence `[P]`); it touches
  `BoundrailKafkaAutoConfiguration`, so it does not overlap another task changing that file. T007 needs T006. T008 needs T007.
- Consumer: T009 needs T005 (the trusted snapshot with its shape checks). T010 needs T009 and T008 (edge publishes in `fail` mode with
  `tenant` bound), and Q2. T011 needs T010 and Q3, Q4.
- US2: T012 needs T005 (same file, `KafkaContext`); it is numbered after US1 by priority and can run any time after T005 that does not
  overlap another change to `KafkaContext`.
- T013 needs T011 (or the D73 fallback decision) and T012. T014 comes last.
- Tasks touching the same file run in sequence (`KafkaContext`, `BoundrailKafkaAutoConfiguration`, `ContextProducer`, `ProducerHeadersTest`,
  `RecordSnapshotTest` and the topology classes are touched by several).
- Every task is committed and reviewed on its own, on branch `007-kafka`.

## Exit Checklist

- [ ] T001's outcome recorded in plan.md, and Q1 – Q4 settled by the user on it
- [ ] Q5 – Q7 settled by the user
- [ ] The answers to Q1 – Q7 written into spec.md and design.md by the spec-creator
- [ ] Scenarios enabled and green on JDK 21 and JDK 25: IT-3.8, IT-4.9
- [ ] Requirements implemented with tests named after their IDs (roadmap traceability row M6): CORE-17, KAF-01 – KAF-05; the Javadoc statement
  of KAF-06 checked by inspection (TDD exception)
- [ ] The guard in T010 (batch `@KafkaListener`) shown red against its temporary change, and the change reverted
- [ ] The consumer adapter works on the ScopedValue backend without set/restore interceptors (IT-3.8 on `integrationTestJdk25`)
- [ ] Every acceptance suite fully green; `boundrail-context-core` still has no runtime dependencies; Kafka confined to `-kafka`
- [ ] `./gradlew check` green
