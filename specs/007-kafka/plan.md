# Implementation Plan: Kafka

**Branch**: `007-kafka` | **Date**: 2026-10-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/007-kafka/spec.md` (Status "Ready for planning"), contract
[contracts/kafka-api.md](contracts/kafka-api.md), decisions D70 – D77 in [design.md](../../docs/design.md) (user-decided 2026-10-18), roadmap
[M6](../../docs/roadmap.md#m6--kafka), the merged code of 002 – 006 (`main` at d6243fc), and the Spring Kafka 4.1.1 and Spring Boot 4.1.1
sources from the Gradle cache (read, not run)

**Status**: Approved by the user on 2026-10-18: plan, tasks, the TDD exception row for the KAF-06 Javadoc (T013) and the expected guard in
[Guard Tests](#guard-tests); Q5 – Q7 ([Spec Gaps](#spec-gaps)) are settled (D78). T001, the spike, ran on 2026-10-18
([Outcome](#outcome-t001-run-2026-10-18)); on its outcome the user settled Q1 – Q4 the same day, accepting every recommendation (D79), and the
spec-creator wrote them into spec.md (KAF-01, KAF-03, KAF-06, Edge Cases, Assumptions), contracts/kafka-api.md and design.md. All of Q1 – Q7
are settled. This revision applies D79 to the plan and to T006 – T011 and T013; it raised Q8 and one new
guard (T011); the user settled Q8 (back off, D80) and approved the guard on 2026-10-18.

## Summary

Context travels with messages under detached semantics: the producer adapter writes the `MESSAGE` keys as record headers on the sending
thread, and the consumer adapter runs each record-mode listener invocation in exactly the snapshot its record carried, which never holds the
deadline or the authority.

- `boundrail-context-spring`: one public interface in the new package `io.github.boundrail.context.spring.internal`, `AdapterSupport`, a bean
  that gives the Kafka module the effective policy, violation building and reporting, `violations.mode` and `wire.max-value-length` (D70).
- `boundrail-context-kafka`, package `io.github.boundrail.context.kafka`:
  - `BoundrailKafkaAutoConfiguration`, ordered after the Spring module's and Boot's Kafka auto-configuration, and the properties record
    `BoundrailKafkaProperties` binding `kafka.trust-headers` (KAF-04, D77);
  - `KafkaContext`, the contract's bean, whose `forRecord` builds a record's snapshot (KAF-02, KAF-04, KAF-05); the listener scope uses the
    same code, so both always agree;
  - a `ProducerPostProcessor` on the `ProducerFactory` of every `KafkaTemplate` bean, which checks and writes headers in every producer that
    factory creates (KAF-01, D71, D79);
  - `ListenerScopeAdvice`, an around-style `MethodInterceptor` placed first in the `ContainerProperties` advice chain of every
    `AbstractKafkaListenerContainerFactory` bean (KAF-02, KAF-03, D72);
  - decorators on each container's `CommonErrorHandler` and, for transactional containers, its `AfterRollbackProcessor`, installed by a
    `SmartLifecycle` one phase before the containers, so error handling runs in the failed record's snapshot (KAF-03, D73, D79);
  - the `KafkaContext` Javadoc of KAF-06 (TDD exception).
- `integration-tests`: Boot's Kafka auto-configuration on the classpath, `spring.kafka.bootstrap-servers` for every service, an edge endpoint
  that publishes to `orders.events`, billing's record-mode `@KafkaListener`, and IT-3.8 and IT-4.9 enabled.

Library code is driven by unit tests that build `ConsumerRecord` values directly, `ApplicationContextRunner` tests, and tests against a
Testcontainers broker for anything that sends through a template or runs a listener container (BLD-07). IT-3.8 and IT-4.9 are enabled in the
task whose code makes them pass (T010), so they are its failing acceptance tests.

## Technical Context

**Language/Version**: Java 21 (`--release 21`); nothing in core's `java25` source set

**Primary Dependencies**:

- core: none (BLD-03).
- `-spring`: nothing new.
- `-kafka`: today `api(core)` and `implementation(spring-kafka)` only. New: `api(project(":boundrail-context-spring"))` (D70) and
  `implementation(libs.spring.boot.autoconfigure)` (T003); tests get `spring-boot-starter-test` (T003), and `spring-boot-kafka` (Boot's Kafka
  auto-configuration) and `testcontainers-kafka` (T006). Spring Kafka 4.1.1, kafka-clients and Spring AOP (for `MethodInterceptor`) come with
  `spring-kafka`, managed by the Spring Boot 4.1.1 BOM. Boot's Kafka auto-configuration is named only by string (`afterName`), so `-kafka`
  has no main dependency on `spring-boot-kafka`.
- `integration-tests`: new `testImplementation(libs.spring.boot.kafka)` (T010), so every service gets Boot's auto-configured `KafkaTemplate`
  and listener container factory, as the spec's scenario preamble requires.
- Catalog: new alias `spring-boot-kafka = { module = "org.springframework.boot:spring-boot-kafka" }`, no version (BOM-managed). The existing
  `spring-boot-starter-kafka` alias stays unused.

**Storage**: N/A

**Testing**:

- `-spring`: `ApplicationContextRunner` for `AdapterSupport` (T002);
- `-kafka`:
  - `ApplicationContextRunner` with `BoundrailContextAutoConfiguration` and `BoundrailKafkaAutoConfiguration` (and Boot's
    `KafkaAutoConfiguration` where a template or factory is needed), a `ContextPolicy` bean, a `SimpleMeterRegistry`, a recording
    `ViolationListener`, and the lazy-initialization setup first used in 003's T006a;
  - `ConsumerRecord` values with `RecordHeaders` built directly for the record-snapshot rules (T004, T005, T012);
  - the advice driven through a `ProxyFactory` around a hand-written record listener, as `ContainerProperties` does (T009);
  - all of them on the ScopedValue backend: `-kafka`'s test runtime classpath holds core's multi-release JAR, and the test task runs on the
    JDK 25 toolchain (T001 item 4); the ThreadLocal backend is exercised only by `integrationTestJdk21` (and, for RuntimeConformanceIT, the
    forced ThreadLocal run);
  - a shared Testcontainers broker (`support/KafkaBroker`, one per test JVM, image `apache/kafka:4.2.1` as in the topology) for templates and
    listener containers (T006 – T008, T010, T011); records are read back with a real `KafkaConsumer` and produced with a real `KafkaProducer`
    when a test needs exact headers;
- DeadlinePropagationIT IT-3.8 and AuthorityPropagationIT IT-4.9 on `integrationTestJdk21` and `integrationTestJdk25`; the other suites
  re-run in T010, which changes shared topology code.

**Target Platform**: JVM 21+, Spring Boot 4.1 applications using Spring Kafka 4.1 `KafkaTemplate` beans and listener container factory beans

**Project Type**: library (Spring Boot auto-configuration)

**Performance Goals**: no reflection per record. A send adds one policy filter of the current snapshot and one header per bound `MESSAGE`
key. A record-mode delivery adds one header lookup per `MESSAGE` key of the effective policy and two nested scope entries (empty snapshot,
then the record's bindings)

**Constraints**:

- Around-style only (KAF-03, principle I): the listener runs as the operation of `ContextSnapshot.call`; no `RecordInterceptor` or other
  set/restore hook scopes anything.
- `AUTHORITY` and `DEADLINE` are never written to or read from a record (POL-06, ID-02, ID-06, D75).
- Kafka dependencies stay in `-kafka` (BLD-05); `-spring` gains no Kafka type. `AdapterSupport` is not part of any contract and CORE-03 does
  not cover it (D70).
- RT-09: nothing new is static; the hook, advice and `KafkaContext` belong to one application context and report through it.
- No reflection on Spring Kafka internals (D71, D72).
- No work from M7: no README change (KAF-06 is the Javadoc only, REL-01 takes it up).

**Scale/Scope**: 1 contract; 7 requirements (CORE-17, KAF-01 – KAF-06); 2 scenarios (IT-3.8, IT-4.9); a spike (T001), 11 red-green tasks
(T002 – T012), two TDD-exception tasks (T013, and T015 from T014's review) and a close task (T014, run after T015)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|---|---|---|
| I Scope-only | Pass | Each record-mode invocation runs as `snapshot(record).call(invocation::proceed)`; error handling runs as the decorated handler's `handleOne`/`handleRemaining`/`process` inside `snapshot.call` (D79); nothing is set or cleared |
| II Declared propagation | Pass | Only keys that propagate to `MESSAGE` are written (POL-04) or read; application keys need a codec and wire name for `MESSAGE` |
| III Secure by default | Pass | `AUTHORITY` and `DEADLINE` excluded in both directions regardless of `trust-headers`; untrusted headers reported, never bound (KAF-04); violations never hold values (DIAG-09) |
| IV Declared, never guessed | Pass | `MESSAGE` is detached: no deadline sent or applied at send (D75); the listener scope replaces the consumer thread's snapshot (KAF-02) |
| V Evidence only | Pass | Violations carry kind, key, `MESSAGE`, an empty destination and DIAG-04's thread evidence; no topic is added to `Violation` (D74) |
| VI Interoperate | Pass | The hooks compose with the application's template interceptor, advice, error handler and after-rollback processor instead of replacing them, without reflection (T001, D79) |
| VII Spec-first, test-first | Pass | Every task but T013 and T015 (TDD exceptions) is one red-green cycle; IT-3.8 and IT-4.9 are T010's failing tests; the TDD exception row and T010's guard are approved; Q1 – Q8 are settled (D78 – D80); T011's empty-snapshot guard (2026-10-18) and its three D82 guards (2026-10-25) are approved; T015's TDD exception row is approved (2026-10-26) |
| VIII Simplicity | Pass | No switch to turn adapters off (D77); one internal interface for D70; no batch scoping (KAF-05); no new public type beyond `KafkaContext` and the properties record (the auto-configuration classes are public but not part of any contract, D81) |
| Technical constraints | Pass | Core unchanged; Spring confined to `-spring`/`-kafka`; Kafka to `-kafka`; Testcontainers for every client interaction, no mocked clients (BLD-07) |

## Spike (T001)

### Findings from the Spring Kafka 4.1.1 sources

Read from `spring-kafka-4.1.1-sources.jar` and checked with `javap`, before the spike. T001 confirms each with running code.

1. **`KafkaTemplate` has no getter for its producer interceptor.** It has `setProducerInterceptor(ProducerInterceptor)` and a private field,
   called in `doSend` through `interceptorProducerRecord`, which lets its exceptions propagate. A post-processor can therefore not read an
   application's interceptor to compose with it, so D71's "the template's own interceptor" cannot both compose and avoid reflection (Q1).
2. **`KafkaTemplate.getProducerFactory()` is public, and `ProducerFactory.addPostProcessor(ProducerPostProcessor)` wraps every producer the
   factory creates.** `doSend` calls `producer.send(interceptedRecord, callback)` after the template's interceptor, with no `catch` around it;
   `observeSend` rethrows a `RuntimeException` unchanged; `CloseSafeProducer.send` delegates without a `catch`. A wrapping `Producer` that
   throws from `send` therefore throws from the template's send call, and the real `KafkaProducer` never sees the record.
3. **`executeInTransaction` calls `callback.doInOperations(this)`** with the target template, so a proxy around a template bean would not see
   sends made inside the callback.
4. **The advice chain proxies only the `MessageListener`.** `ContainerProperties.setMessageListener` and `setAdviceChain` wrap the listener
   in a `ProxyFactory` with the chain, in order (the first advice is outermost). If the listener is already an AOP proxy, the chain is added
   after its existing advisors. `AbstractKafkaListenerContainerFactory.initializeContainer` copies the factory's container properties,
   including the advice chain, into each container it creates, by `@KafkaListener` or by `createContainer(...)`; the
   `ContainerCustomizer` and an endpoint's `ContainerPostProcessor` run afterwards and could replace the chain.
5. **`RecordInterceptor` and the error handler run outside the proxied listener.** In `KafkaMessageListenerContainer.ListenerConsumer`,
   `doInvokeOnMessage` calls `recordInterceptor.intercept` before `listener.onMessage`; `doInvokeRecordListener` calls the interceptor's
   `success`/`failure` and, in its `catch`, `invokeErrorHandler` (`CommonErrorHandler.handleOne` or `handleRemaining`) after the proxied call
   has returned. A deserialization failure throws in `invokeOnMessage` before the listener is called. So with the advice alone, application
   `RecordInterceptor`s and the error handler run outside the record's snapshot (Q2, Q3).
6. **Error handler access.** The factory has `setCommonErrorHandler` but no getter; each container has `getCommonErrorHandler` and
   `setCommonErrorHandler`. With no handler set, the container creates a `DefaultErrorHandler` internally. Boot's
   `ConcurrentKafkaListenerContainerFactoryConfigurer` passes a `CommonErrorHandler` bean to the factory.

### What T001 confirms

On a scratch project with a Testcontainers broker, against the packaged JARs where the backend matters; no code kept, nothing committed.

1. **Producer (D71, Q1).**
   - A `ProducerPostProcessor` added to a `KafkaTemplate` bean's `ProducerFactory` by a bean post-processor: an exception from the wrapper's
     `send` reaches the caller unchanged from `send(topic, value)`, `send(ProducerRecord)`, `send(Message<?>)`, `sendDefault`, with
     observation on and off, and from a send inside `executeInTransaction` (the transaction is aborted); the record is not delivered.
   - The application's own template interceptor runs, before the wrapper, and headers it adds are visible to the wrapper.
   - What else the wrapper covers: `execute(ProducerCallback)`, a template that is not a bean but shares the factory, and
     `factory.createProducer()` (all covered, which KAF-01 and KAF-06 say are not).
   - A producer created before the post-processor is added, and transactional producers.
   - Confirm finding 3 (a template proxy misses sends in `executeInTransaction`).
2. **Listener scope (D72, Q2).**
   - A bean post-processor on Boot's `ConcurrentKafkaListenerContainerFactory` and an application factory bean prepends the advice to
     `getContainerProperties().getAdviceChain()`; the advice reaches containers from `@KafkaListener` and from `createContainer(...)`.
   - The advice sees `onMessage(ConsumerRecord, Acknowledgment, Consumer)` for a `@KafkaListener` method, with `getThis()` the adapter; a
     batch listener's adapter is a `BatchMessageListener`; other methods (`onPartitionsAssigned`, …) also pass through the proxy.
   - The application's advice, placed after the library's, runs inside the record's snapshot; an application `RecordInterceptor` runs
     outside it (finding 5); a `ContainerCustomizer` calling `setAdviceChain` removes the library's advice.
   - On JDK 25 with the packaged core JAR (ScopedValue backend), the listener sees the record's bindings and the consumer thread has none
     afterwards.
3. **Error handling (D73, Q3, Q4).** Prototype a `CommonErrorHandler` decorator that delegates every method and runs `handleOne` and
   `handleRemaining` in the snapshot of the failed record (the first of the list), installed per container before it starts: by a
   `SmartLifecycle` in an earlier phase than the containers that wraps the handler of every container in the `KafkaListenerEndpointRegistry`
   and every `MessageListenerContainer` bean (setting an explicit `DefaultErrorHandler` where none is set). Check: a
   `DeadLetterPublishingRecoverer` through a template bean publishes in `fail` mode with a required `MESSAGE` key; the previous snapshot is
   restored; containers registered after startup; a deserialization failure; a listener whose decode threw; transactional containers, which
   use an `AfterRollbackProcessor`.
4. **Backend of `-kafka`'s unit tests.** Record whether `:boundrail-context-kafka:test` loads core from its classes or its multi-release JAR on
   the JDK 25 toolchain, so the plan states which backend the unit tests exercise.
5. **Topology timing.** Whether billing needs a `NewTopic("orders.events")` bean for its consumer to be assigned before the first record is
   published (the broker auto-creates topics on first send).

Outcome into this section, with a recommendation for Q1 – Q4. If a mechanism cannot meet a requirement, the requirement is revised with the
user before implementation (spec Assumptions); for D73 the fallback is to document that error handling runs outside the scope (KAF-06).

### Outcome (T001, run 2026-10-18)

Scratch project outside the repository (`~/.claude/jobs/1c2be776/tmp/spike-007/proj`, deleted with the job): Spring Boot 4.1.1 BOM
(Spring Kafka 4.1.1), `spring-boot-kafka`, Testcontainers `apache/kafka:4.2.1`, and the repo's packaged
`boundrail-context-core/build/libs/boundrail-context-core.jar` (multi-release). Stand-ins: one `MESSAGE` key `tenant` (`Tenant-Id`), a
"required" check in `fail` mode that throws a marker exception, and a header `Boom-Decode` whose presence makes "decode" throw an
`IllegalStateException`. Run: `./gradlew test` on JDK 25 (15 tests, 0 failures; BUILD SUCCESSFUL in 2m 53s), and the listener test also with
`./gradlew testJdk21`. Nothing from it is in the repository.

1. **Producer (D71): confirmed, with three limits.**
   - A `ProducerPostProcessor` added by a bean post-processor to `getProducerFactory()` of every `KafkaTemplate` bean, whose wrapper throws
     from `send` when the key is unbound, makes these throw the wrapper's exception unchanged (same class, no cause) on the calling thread:
     `send(topic, v)`, `send(topic, k, v)`, `send(topic, p, k, v)`, `send(topic, p, ts, k, v)`, `send(ProducerRecord)`, `send(Message<?>)`,
     `sendDefault(v)` and `sendDefault(k, v)`, with `spring.kafka.template.observation-enabled` `false` and `true`. With observation on, a real
     `ObservationRegistry` saw 10 `spring.kafka.template` starts and 8 errors carrying the exception. None of the failed records reached the
     topic; the bound sends that followed were delivered with `Tenant-Id=acme`.
   - `executeInTransaction`: the exception propagates from `executeInTransaction`; a callback whose first send succeeded and whose second
     threw was aborted (a `read_committed` reader saw only the later committed record, which had `Tenant-Id`). Transactional producers are
     created through the post-processor too.
   - Composition: an application template bean with `setProducerInterceptor` runs its interceptor first; the wrapper then sees the header it
     added, and a `Tenant-Id` the interceptor set is kept unchanged (D74).
   - Finding 3 confirmed: a CGLIB proxy around the template counted 0 sends inside `executeInTransaction`; the callback received the target.
   - Also covered, as the plan expected: `execute(ProducerCallback)`, a `KafkaTemplate` that is not a bean but shares the factory, a template
     built with config overrides (its copied factory copies the post-processors), and `factory.createProducer()`.
   - Not covered: a producer the factory created and cached before the post-processor was added (the next send was not checked; after
     `factory.reset()` it was); and a `ProducerFactory` that is not a `DefaultKafkaProducerFactory`, because `addPostProcessor` is a no-op
     interface default, so the template is silently uncovered (`getPostProcessors()` stayed empty and the record went out unchecked).
   - Note: `KafkaTemplate.doSend` does not close the producer when `send` throws; harmless for the shared non-transactional producer (later
     sends worked) and for `executeInTransaction` (the template closes it).
2. **Listener scope (D72): confirmed, on both backends.**
   - Prepending the advice in a bean post-processor on Boot's `kafkaListenerContainerFactory` and on an application factory reached
     `@KafkaListener` containers of both, and a container bean made with `appFactory.createContainer(topic)`. The advice ran once per record
     (6 records, 6 scoped invocations), also with a `ConcurrentMessageListenerContainer`.
   - What it sees: for a `@KafkaListener` method, `onMessage(ConsumerRecord, null, ExtendedKafkaConsumer)` declared by
     `AcknowledgingConsumerAwareMessageListener`, with `getThis()` a `RecordMessagingMessageListenerAdapter`; for a lambda container,
     `onMessage(ConsumerRecord)` with `getThis()` the lambda; with a `RecordFilterStrategy` bean, `getThis()` is a
     `FilteringMessageListenerAdapter`. A batch listener's `getThis()` is a `BatchMessagingMessageListenerAdapter` (or
     `FilteringBatchMessageListenerAdapter`), both `BatchMessageListener`s, called with `onMessage(ArrayList, null, ExtendedKafkaConsumer)`;
     it was passed through and saw no `tenant`. Other methods also pass through the proxy: `isAsyncReplies`, `wantsPollResult` and
     `getDelegate` on the startup thread, and `registerSeekCallback`, `onPartitionsAssigned` and `onFirstPoll` on the consumer thread.
   - The application's advice set on its factory in the `@Bean` method ran inside the scope (`tenant=acme`). Application `RecordInterceptor`s
     (a bean on Boot's factory, and one set on the application factory) saw no `tenant` in `intercept`, `success`, `failure` and `afterRecord`
     (finding 5); `afterRecord` also shows the consumer thread has no binding after each record.
   - A `ContainerCustomizer` calling `setAdviceChain(appAdvice)` removes the library's advice only where the listener is set after the
     customizer runs: a `createContainer` container lost the scope (`tenant` unbound), but a `@KafkaListener` container kept it, because its
     listener is already proxied when the factory copies the chain, and `setAdviceChain` on a proxied listener only removes the advices of
     the new chain before adding them. A customizer that appends to `getAdviceChain()` keeps the library's advice outermost.
   - JDK 25 with core loaded from the packaged JAR: backend `SCOPED_VALUE`, listeners saw `tenant=acme` for the record that carried it and
     nothing for the next one. JDK 21: backend `THREAD_LOCAL`, same results.
3. **Error handling (D73): confirmed, with a decorator installed per container before it starts.**
   - The prototype `CommonErrorHandler` decorator delegates every method and runs `handleOne` and `handleRemaining` in the snapshot of the
     failed record (the first of the list). A `SmartLifecycle` with phase `AbstractMessageListenerContainer.DEFAULT_PHASE - 1` wraps the
     handler of every container from `KafkaListenerEndpointRegistry.getAllListenerContainers()` (registry containers and
     `MessageListenerContainer` beans) and sets a wrapped `DefaultErrorHandler` where none is set on a non-transactional container. The
     parent's handler is copied to the child containers when they start.
   - It must look the registry up when it starts (`ObjectProvider`), not take it as a constructor argument: with the registry injected,
     Spring started the registry, a dependency, before the installer, and the installer saw `running=true` and wrapped too late (first run).
   - With a `DefaultErrorHandler` bean holding a `DeadLetterPublishingRecoverer` (no retries) and the producer check in `fail` mode, a
     listener that threw had its record published to `<topic>-dlt` with `Tenant-Id=acme`, through the `handleRemaining` path (default) and
     the `handleOne` path (`setSeekAfterError(false)`), for a `@KafkaListener` container and a `createContainer` bean. The decorator was
     entered with no binding (the error handler runs outside the listener's scope), and the `RecordInterceptor.afterRecord` that followed saw
     none, so the previous snapshot is restored.
   - Control without the installer: the recoverer saw no `tenant`, the dead-letter send threw the producer check's exception, the
     recoverer logged "Dead-letter publication ... failed" and the record was redelivered and failed again in a loop; nothing reached the
     DLT. This is the failure D73 prevents.
   - Deserialization failure (`ErrorHandlingDeserializer` around a deserializer that throws for `poison`, a `DeadLetterPublishingRecoverer`
     with a `byte[]` template): the listener was not invoked, the decorator built the snapshot from the record's headers, and the raw record
     reached the DLT with `Tenant-Id=acme`. A failure inside `poll()` without `ErrorHandlingDeserializer` reaches `handleOtherException`,
     which has no record (not run).
   - Decode exception (KAF-02's non-`IllegalArgumentException`): the advice threw before the listener, which was not invoked; the decorator's
     rebuild threw again and it used the empty snapshot (Q4). In `fail` mode with a required `MESSAGE` key the dead-letter send then fails,
     so the record is redelivered in a loop (4 attempts in 8 seconds).
   - Late containers: a container created from Boot's factory and started after startup had the advice (listener saw `tenant=acme`) but an
     undecorated handler; its dead-letter send failed.
   - Transactional containers (`spring.kafka.producer.transaction-id-prefix`, Boot's `KafkaTransactionManager`): the container has no
     `CommonErrorHandler` and uses its `AfterRollbackProcessor`, so the installer must not set a `DefaultErrorHandler` there. A
     `DefaultAfterRollbackProcessor` bean with a recoverer ran outside the scope and its dead-letter send failed in a loop. The same
     decorator pattern on `getAfterRollbackProcessor()`/`setAfterRollbackProcessor` ran `process` in the first record's snapshot, and the
     DLT record was committed with `Tenant-Id=acme` (`read_committed` reader).
   - Not run: containers registered at runtime through `registry.registerListenerContainer(..., true)` (same gap as late containers),
     `@RetryableTopic` containers, and restarting a container.
4. **Backend of `-kafka`'s unit tests.** An init script outside the repository printed `:boundrail-context-kafka`'s and
   `:boundrail-context-spring`'s `testRuntimeClasspath`: both load `boundrail-context-core/build/libs/boundrail-context-core.jar`, the
   multi-release JAR, not its classes directory. A probe test added to `:boundrail-context-kafka:test` by a second init script (no
   tracked file changed; its outputs were deleted afterwards) printed `java.version=25.0.4.1 backend=SCOPED_VALUE`. So `-kafka`'s unit tests
   run on the ScopedValue backend; nothing runs them on ThreadLocal. The Risks entry "ScopedValue proof only in `integration-tests`" does
   not hold: the unit tests also prove it, and ThreadLocal is covered only by `integrationTestJdk21`.
5. **Topology timing.** A billing-like Boot app with a record `@KafkaListener` on a topic that did not exist yet, `auto-offset-reset=earliest`
   and no `NewTopic`: assigned 660 ms after startup, and a record published right after startup arrived 77 ms after it was sent; a record
   published before the app started was also received. With `NewTopic` it was the same (12 ms). With `latest` a record sent right after
   startup was lost once (with `NewTopic`). So billing needs no `NewTopic`; `auto-offset-reset=earliest`, already in the plan, is what makes
   delivery safe.

### Recommendations for Q1 – Q4

- **Q1: option (a), the `ProducerPostProcessor`.** It meets D71: composes with the application's interceptor, throws from every send call
  and `executeInTransaction`, runs on the sending thread, needs no reflection. KAF-01's coverage and KAF-06's list change to "every send
  through a producer that the `ProducerFactory` of a `KafkaTemplate` bean creates after startup", which includes `execute(ProducerCallback)`,
  non-bean templates sharing that factory and `createProducer()`. KAF-06 should also name the two gaps found: a `ProducerFactory` that is not
  a `DefaultKafkaProducerFactory` (silently uncovered), and producers created before the template bean was initialized.
- **Q2: revise KAF-03 as the plan recommends, more precisely.** The application's advice in a factory's chain runs inside the scope;
  `RecordInterceptor`s run outside it. A chain replaced by a `ContainerCustomizer` or `ContainerPostProcessor` removes the scope only from
  containers whose listener is set after it (`createContainer`); `@KafkaListener` containers keep it. KAF-06 states both as "not covered when
  the application replaces the chain", without promising the `@KafkaListener` case.
- **Q3: the decorator, extended to transactional containers.** A `CommonErrorHandler` decorator, and an `AfterRollbackProcessor` decorator
  for containers with a transaction manager, installed by a `SmartLifecycle` one phase before the containers that looks up the registry when
  it starts. It sets a `DefaultErrorHandler` only on non-transactional containers without a handler. Not covered, stated in KAF-06:
  containers registered or created after startup, `handleOtherException`, `handleBatch` and `processBatch` (KAF-05). If the user prefers to
  leave `AfterRollbackProcessor` out, KAF-06 says transactional containers' after-rollback handling runs outside the scope.
- **Q4: as recommended (failed record's snapshot, rebuilt without reporting; empty when rebuilding throws), with one consequence to
  accept.** For a decode exception in `fail` mode with a required `MESSAGE` key, a dead-letter recoverer cannot publish in the empty
  snapshot, so the record is redelivered until the application handles it, the same as WIRE-05's "propagates" elsewhere. The alternative,
  binding the keys that decoded, is behaviour the spec does not define.

### Contradictions with the spec and plan

Resolved on 2026-10-18: the user accepted the four recommendations above (D79); spec.md, the contract and design.md were revised, and this
plan and tasks.md now follow them (Q3's `AfterRollbackProcessor` decorator, the installer's registry lookup at start, the ScopedValue
backend of the unit tests, and the one-entry listener scope in [Listener scope](#listener-scope-kaf-02-kaf-03-d72)).

- KAF-01 and KAF-06 (D71) say `execute(ProducerCallback)`, non-bean templates and directly created producers are not covered; with Q1 (a)
  those sharing the template bean's factory are (Q1).
- KAF-03 says application interceptors run inside the scope; `RecordInterceptor`s cannot (Q2).
- Plan, Q3 row: "transactional containers' `AfterRollbackProcessor`" is listed as not covered; the spike shows it can be (Q3). The plan's
  installer wording should say the registry is looked up at start, and that no `DefaultErrorHandler` is set on transactional containers.
- Plan, Risks: "`-kafka`'s unit tests may run on the ThreadLocal backend" is answered: they run on ScopedValue (item 4).
- Plan, Research Notes: the listener scope can be `forRecord(record).call(invocation::proceed)`, one scope entry per record instead of two
  (the snapshot is exact, and `call` replaces the current one, CORE-10); the spike used it. Building the snapshot still enters a temporary
  scope (Q7), so the `ScopeListener` count per record stays two.

## Research Notes

### Spring side (D70)

- **`AdapterSupport` (T002).** A public interface in `io.github.boundrail.context.spring.internal`:
  `ContextPolicy policy()` (the effective policy, POL-07), `boolean failOnMissingRequired()` (`violations.mode=fail`), `int maxValueLength()`
  (`wire.max-value-length`), `Violation violation(Violation.Kind, String key, Boundary)` (built through `ViolationReporter.violation` with an
  empty destination, so DIAG-04's evidence is the same as HTTP's) and `void report(Violation)`. Its one implementation is an anonymous
  class in a `@Bean AdapterSupport boundrailAdapterSupport(EffectivePolicy, ViolationReporter, BoundrailContextProperties)` method
  of `BoundrailContextAutoConfiguration`. `EffectivePolicy` and `ViolationReporter` stay package-private. An interface rather than a class,
  because a public class in another package could not take the package-private types in its constructor (Q6).

### Kafka auto-configuration and properties

- **`BoundrailKafkaAutoConfiguration` (T003).** `@AutoConfiguration(after = BoundrailContextAutoConfiguration.class, afterName =
  "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")`, `@ConditionalOnClass(KafkaTemplate.class)`,
  `@ConditionalOnBean(AdapterSupport.class)` (KAF-04 activation; backing off when the Spring module's auto-configuration is excluded, Q8),
  `@EnableConfigurationProperties(BoundrailKafkaProperties.class)`; registered in `boundrail-context-kafka/src/main/resources/META-INF/spring/
  org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- **`BoundrailKafkaProperties`.** `@ConfigurationProperties("boundrail.context.kafka") public record BoundrailKafkaProperties(
  @DefaultValue("true") boolean trustHeaders)`. Boot's boolean conversion accepts `true`/`on`/`yes`/`1` and their opposites in any case; any
  other value fails binding. The `@Lazy(false)` `KafkaContext` bean depends on it, so binding happens at startup even with lazy
  initialization (KAF-04, the same pattern as `audit.enabled`).
- **`KafkaContext` bean.** `@Bean @Lazy(false) KafkaContext boundrailKafkaContext(AdapterSupport, BoundrailKafkaProperties)`; the class has a
  package-private constructor (contract: no public constructor).

### Record snapshot (KAF-02, KAF-04, KAF-05)

- **One implementation.** `KafkaContext.forRecord(record)` checks `null` (CORE-17) and calls the package-private `snapshot(record)`, which the
  listener advice also calls, so the snapshot `forRecord` returns is by construction the one KAF-02 binds.
- **Trusted (`trust-headers=true`).** For each key in `policy.keys()`, except `AUTHORITY` and `DEADLINE`, that propagates to `MESSAGE` and has
  a wire name: `record.headers().headers(wireName)` (Kafka compares header keys with `equals`, so exactly, WIRE-06):
  1. none → skip; more than one → `INVALID_VALUE`, nothing decoded;
  2. a `null` value → `INVALID_VALUE`;
  3. `new String(bytes, UTF_8)` (malformed sequences replaced by U+FFFD) longer than `maxValueLength()` → `OVERSIZED_VALUE` (WIRE-04, D35's
     UTF-16 code units; checked before the UTF-8 check, so a long malformed value is reported once);
  4. a strict decode (`UTF_8.newDecoder()` with `CodingErrorAction.REPORT`) failing → `INVALID_VALUE`;
  5. `Initiator.of` for `INITIATOR`, otherwise the key's codec; `IllegalArgumentException` → `INVALID_VALUE` (WIRE-05); anything else
     propagates, with no violation.
  Every other header, including `Authorization`, `Boundrail-Deadline-Ms`, keys that do not propagate to `MESSAGE` and unknown names, is not
  looked at.
- **Untrusted (`trust-headers=false`).** No binding, no decode; for each key in `policy.keys()` with a wire name (including
  `Boundrail-Deadline-Ms` and keys that do not propagate to `MESSAGE`), one `UNTRUSTED_HEADER` if `headers(wireName)` has any element
  (`null` values included). `Authorization` is no policy wire name, so it is never reported.
- **Violations.** `support.violation(kind, key.name(), Boundary.MESSAGE)` then `support.report(...)`: empty destination (DIAG-08), no value
  (DIAG-09), reported in the context that owns the `KafkaContext` bean, which is the context of the listener containers its advice is added to.
- **Building the snapshot.** Core has no public snapshot factory, so the decoded bindings become a `ContextBinding` and the snapshot is
  `ContextSnapshot.EMPTY.call(() -> binding.call(Context::capture))`, or `ContextSnapshot.EMPTY` when nothing was decoded. Nothing stays
  current after `forRecord` returns, but `ScopeListener`s such as the MDC bridge see nested enter/exit pairs on the caller's thread (Q7,
  Q9, D78). `rebuild(record)` (T011) is the same build with reporting off.

### Producer (KAF-01, D71)

Settled by T001 and Q1 (D79): a `ProducerPostProcessor` on the template bean's `ProducerFactory`.

- **Hook.** A `static` bean post-processor `TemplatePostProcessor` (it takes `ObjectProvider<AdapterSupport>`, so it creates nothing early)
  adds one `ContextProducerPostProcessor` to the `ProducerFactory` of every `KafkaTemplate` bean, subclasses included, unless that factory's
  `getPostProcessors()` already holds one (two templates sharing a factory). The post-processor wraps each `Producer` in `ContextProducer`,
  which delegates every method and overrides both `send` methods.
- **`ContextProducer.send`, on the calling thread, before delegating:**
  1. required-key check: one `MISSING_REQUIRED` per key with `policy.isRequired(key) && policy.propagates(key, MESSAGE) && !key.isBound()`,
     sorted by name, all reported; with `failOnMissingRequired()` throw `ContextViolationException(first)` and do not delegate (POL-08,
     DIAG-02);
  2. `policy.filter(Context.capture(), MESSAGE)` (POL-04, which already excludes `AUTHORITY` and `DEADLINE`);
  3. per bound key with a wire name: skip if `record.headers().headers(wireName)` has any element (D74); encode (`Initiator.value()` or the
     codec; exceptions propagate, nothing reported, not delegated, WIRE-05); longer than `maxValueLength()` → `OVERSIZED_VALUE`, not written;
     otherwise `record.headers().add(wireName, encoded.getBytes(UTF_8))`. No WIRE-08 check (D74).
  The deadline is not read (D75).
- **Composition.** The application's template interceptor runs in `doSend` before `producer.send`, so a header it adds counts as set by the
  application. Nothing of the template's own configuration is replaced.
- **Coverage (KAF-01, D79).** Every producer the hooked factory creates after the post-processor is added: all template send methods,
  `executeInTransaction`, `execute(ProducerCallback)`, templates that are not beans but share the factory (including a template built with
  config overrides, whose copied factory copies the post-processors), and `factory.createProducer()`. Not covered, and stated in KAF-06: a
  `ProducerFactory` that is not a `DefaultKafkaProducerFactory` (its `addPostProcessor` is the interface's no-op default), and a producer the
  factory created and cached before the hook was added. No test pins these gaps; T013's Javadoc states them.
- **Re-sending a record (KAF-01, D81).** Step 3 writes into the application's own `ProducerRecord`, with no copy. Sending the same record
  instance again is outside KAF-01: Kafka makes its headers read-only once sent, so a later `add` may throw `IllegalStateException`, and
  headers written before an `encode` that throws may stay on the record. No test; T013's Javadoc states it (KAF-06).

### Listener scope (KAF-02, KAF-03, D72)

- **`ListenerScopeAdvice` (T009).** A package-private `MethodInterceptor` holding the `KafkaContext`. If `invocation.getThis()` is a
  `BatchMessageListener`, the method is not `onMessage`, or the first argument is not a `ConsumerRecord`, it returns `invocation.proceed()`
  (KAF-05; other listener methods, which T001 saw on the startup and consumer threads). Otherwise it returns
  `context.snapshot(record).call(invocation::proceed)`: one scope entry for the invocation, as the spike did.
  - Consistent with KAF-05's reporting rules: `snapshot(record)` is the code `forRecord` runs (without its `null` check, which cannot fail
    here), so the listener is bound with exactly the snapshot `forRecord` would return, and the delivery reports its violations once, in that
    build, as KAF-02 requires; a redelivery builds and reports again.
  - `snapshot.call` replaces the consumer thread's snapshot (CORE-10) and restores it, also for `ContextSnapshot.EMPTY` (always entering a
    scope, D73), and rethrows the listener's exception unchanged (CORE-08). `invocation.proceed()` declares `Throwable`;
    `ContextCallable<R, X>` carries it.
  - A non-`IllegalArgumentException` from `decode` propagates from `snapshot(record)` before any scope is entered, so the listener is not
    invoked.
  - `ScopeListener`s see several enter/exit pairs per record: the temporary scopes that build the snapshot (D78, Q9) and the invocation's.
- **Wiring (T010).** A `static` bean post-processor `ListenerContainerFactoryPostProcessor` sets, on every
  `AbstractKafkaListenerContainerFactory` bean after initialization, `getContainerProperties().setAdviceChain(advice, existing...)`, unless the
  first element already is the library's advice. The application's advice set in its `@Bean` method is in `existing`, so it runs inside.
- **Error handling (T011, D73, D79).**
  - `ContextErrorHandler implements CommonErrorHandler` (package-private) delegates every method and runs `handleOne` and `handleRemaining` in
    the snapshot of the first record it is given. `ContextAfterRollbackProcessor implements AfterRollbackProcessor` does the same for
    `process`; every other method, including `processBatch`, `handleBatch` and `handleOtherException`, is delegated unscoped.
  - The snapshot comes from `KafkaContext.rebuild(record)` (package-private): the same build as `snapshot(record)`, under the same trust
    setting, but reporting nothing, so one delivery is never counted twice. If it throws, the handler runs in `ContextSnapshot.EMPTY` (so a
    record whose decode threw still reaches the application's handler; with a required `MESSAGE` key in `fail` mode its dead-letter send then
    fails and the record is redelivered, as KAF-03 states).
  - `ErrorHandlingInstaller implements SmartLifecycle` (package-private), phase `AbstractMessageListenerContainer.DEFAULT_PHASE - 1`, takes
    `ObjectProvider<KafkaListenerEndpointRegistry>` and looks the registry up in `start()`; injecting the registry directly made Spring
    start it, a dependency, before the installer, which then wrapped too late (T001). In `start()`, for every container of
    `getAllListenerContainers()` (registry containers and `MessageListenerContainer` beans) that is an `AbstractMessageListenerContainer`:
    - every container: wrap its `CommonErrorHandler` where one is set (KAF-03 decorates the handler of each container, transactional or
      not);
    - non-transactional with no handler: set a decorated `new DefaultErrorHandler()` (what the container would have created itself);
    - transactional: also wrap its `AfterRollbackProcessor`, and set no handler where none is set. "Transactional" means
      `getKafkaAwareTransactionManager() != null || getTransactionManager() != null` on its container properties, the deprecated getter
      included, as the container itself decides (Boot's factory sets the first from `KafkaTransactionManager`);
    - never wrap twice (a handler already a `ContextErrorHandler` is kept). Child containers of a `ConcurrentMessageListenerContainer` copy
      the parent's handler when they start.
  - Not covered (KAF-03, KAF-06): containers created or registered after startup, `MessageListenerContainer` beans whose phase is lower
    than `DEFAULT_PHASE` (D82), `handleOtherException`, and the batch paths.

### Integration tests

- **Topology (T010).**
  - `Topology.startService` adds `spring.kafka.bootstrap-servers=<broker>` for every service, and for billing
    `spring.kafka.consumer.group-id=billing` and `spring.kafka.consumer.auto-offset-reset=earliest`; no `NewTopic` (T001 item 5:
    `earliest` makes delivery safe).
  - `EdgeApp`: `POST /kafka/publish` sends the request's marker as the value to `orders.events` through Boot's auto-configured
    `KafkaTemplate` (string serializers by default) and waits for the send result (`get(10, SECONDS)`), so a send failure is a 500. The deadline
    of DeadlinePropagationIT's edge is not applied to the send (D75).
  - `BillingApp`: a record-mode `@KafkaListener(topics = "orders.events")` on Boot's factory taking `ConsumerRecord<String, String>`; it
    sleeps until `record.timestamp() + 4s` (D77), then records under `billing` an observation from `ObservedContext.consumed(record)`.
  - `ObservedContext.consumed(record)`: the marker (the value) under the existing marker entry, `kafka.header-names` (sorted, comma-joined),
    `kafka.delay-millis` (processing start minus the record timestamp), and the same `ctx.*` entries as `ping`.
- **"Returns normally with exactly one observation" (D77).** The listener records at its start, so a failure would be retried by the default
  error handler and record again. The scenario waits up to 30 seconds for the first observation, then 2 more seconds, and asserts exactly one.
- **Shared JVM.** Observations are keyed by marker; both scenarios assert their own marker only. Billing processes records serially, so each
  scenario takes at least 4 seconds.

### Verify commands

Unit: `./gradlew :<project>:test --tests '<Class>'`. Scenarios (Gradle applies `--tests` to the task just before it):

```bash
./gradlew --continue \
          :integration-tests:integrationTestJdk21 --tests '*DeadlinePropagationIT' --tests '*AuthorityPropagationIT' \
          :integration-tests:integrationTestJdk25 --tests '*DeadlinePropagationIT' --tests '*AuthorityPropagationIT'
```

T010 changes shared topology code, so it also runs `*ContextPropagationIT`, `*TrustBoundaryIT` and `*TopologySmokeIT` the same way. Every task
ends with `./gradlew check`.

## Spec Gaps

Q1 – Q7 are settled: Q5 – Q7 by the user with the recommended answers (D78), Q1 – Q4 by the user on T001's outcome on 2026-10-18, each with
the recommendation (D79). The spec-creator wrote them into spec.md, contracts/kafka-api.md and design.md. Q8, from applying D78 to T003,
is settled (D80). Q9, from planning T015 (T014's review), was settled by the user on 2026-10-26: `forRecord` keeps its two scopes.

| Gap | Status | Question | Decision | Tasks |
|---|---|---|---|---|
| Q1 | Closed (D79) | Which producer hook meets D71? `KafkaTemplate` 4.1.1 has no getter for its interceptor (finding 1) | A `ProducerPostProcessor` on the `ProducerFactory` of each `KafkaTemplate` bean. Covered: every send through a producer that factory creates, including `execute(ProducerCallback)`, templates that are not beans but share the factory, and producers created directly from it (KAF-01). Not covered, stated in KAF-06: a `ProducerFactory` that is not a `DefaultKafkaProducerFactory`, and producers created and cached before the hook was added | T006 – T008, T013 |
| Q2 | Closed (D79) | KAF-03 said application interceptors run inside the scope, but the advice chain wraps only the listener (finding 5); a replaced chain loses the advice (finding 4) | Advice the application adds to a factory's chain runs inside the scope; `RecordInterceptor`s (`intercept`, `success`, `failure`, `afterRecord`) run outside it; a container whose chain the application replaces is not covered, and KAF-06 does not promise that a `@KafkaListener` container keeps the scope then | T010, T013 |
| Q3 | Closed (D79) | Which around-style hook runs the error handler inside the record's snapshot (D73)? | Decorators on each container's `CommonErrorHandler` and, for containers with a transaction manager, its `AfterRollbackProcessor` (`handleOne`, `handleRemaining`, `process`), installed by a `SmartLifecycle` one phase before the containers that looks the `KafkaListenerEndpointRegistry` up when it starts (`ObjectProvider`); a decorated `DefaultErrorHandler` only on non-transactional containers without a handler, none on transactional ones. Not covered: containers created or registered after startup, `MessageListenerContainer` beans whose phase is lower than `DEFAULT_PHASE` (D82), `handleOtherException`, `handleBatch` and `processBatch` | T011, T013 |
| Q4 | Closed (D79) | Which failures does D73 cover, and with which snapshot? | Every record the decorators handle (including deserialization failures), in the failed record's snapshot rebuilt without reporting again; the empty snapshot when rebuilding throws. Accepted consequence: a record whose decode throws, with a required `MESSAGE` key in `fail` mode, cannot be dead-lettered and is redelivered | T011, T013 |
| Q5 | Closed (D78) | D77's activation conditions were in design.md only | Now KAF-04: the Kafka auto-configuration is active whenever Spring Kafka and `boundrail-context-spring` are on the classpath, with no off switch; `spring-kafka` stays `implementation` | T003 |
| Q6 | Closed (D78) | The properties record's and the `spring.internal` type's names | `BoundrailKafkaProperties`; the interface `spring.internal.AdapterSupport` | T002, T003 |
| Q7 | Closed (D78) | `forRecord` builds its snapshot in a temporary scope | Accepted and in KAF-05: the snapshot is not current when `forRecord` returns, and `ScopeListener`s may see one enter/exit pair per call | T004 |
| Q8 | Closed (D80) | KAF-04 (D78) makes the Kafka auto-configuration active whenever both libraries are on the classpath. What happens when an application excludes `BoundrailContextAutoConfiguration` while keeping `-kafka`? There is then no `AdapterSupport`, so an unconditional Kafka auto-configuration fails startup with an unsatisfied dependency | Back off: keep `@ConditionalOnBean(AdapterSupport.class)` so the Kafka adapters are inactive when the Spring module's auto-configuration is excluded, and add that sentence to KAF-04 (it was D77's second condition). The alternative, failing startup with a clear message, adds a check nobody asked for | T003 |
| Q9 | Closed (user, 2026-10-26; KAF-05, D78) | T014's review asked for `forRecord` to enter one scope per call (today two: `ContextSnapshot.EMPTY`, then the binding). A single binding would remove every key of `Context.capture()` with `ContextBinding.without`, but `without(DEADLINE)` throws `IllegalArgumentException` (DL-11), so a caller's snapshot that binds `DEADLINE` could not be cleared (KAF-02, KAF-05); core has no public snapshot factory and stays unchanged in 007 | Option (b): keep `forRecord`'s two scopes; KAF-05 and D78 now say scope listeners may observe enter and exit pairs per call (no longer "one"). No red test; T015 changes `forRecord`'s Javadoc and contracts/kafka-api.md to match | T015 |

## Guard Tests

These tests already pass before the task that introduces them. As in 002 – 006, each red is shown against a named temporary change, which
is reverted before the commit. They are not TDD exceptions. The user approved T010's row on 2026-10-18, T011's row on 2026-10-18, and T009's
two rows on 2026-10-22, and T008's follow-up row on 2026-10-21, and T011's three D82 rows and T012's two rows on 2026-10-25. On
2026-10-24 the user approved the amended T010 row, which names two temporary changes: the original single change could not report a
repeated header, because both records arrive in one batch whose first record has a single `Tenant-Id`. The `RecordInterceptor` assertions
in T010 and the "reported once" assertion in T011 sit in tests with red positive assertions, so they are not guards.

A test that is green before its task is accepted as a guard when its red is shown against a named temporary change to the finished code, which
is then reverted; the leader reports it in the task report, the planner adds it to this table, and the reviewer checks it.

A negative assertion that sits in the same test as a positive assertion that is red before its task is not a guard, and is not listed (for
example, "no `Authorization` or `Boundrail-Deadline-Ms` header" sits with "a header per bound `MESSAGE` key" in T006, and "keys that do not
propagate to `MESSAGE` are not checked" with the `MISSING_REQUIRED` assertions in T008). Likewise, parametrized rows that sit in the same
test as rows that are red before its task are not guards, and are not listed (for example, T007's `256 a` and `256 é` rows, which are
written, sit with the red `257 a` row).

| Task | Scenario or test | Why green first | Red shown against (temporary, reverted) |
|---|---|---|---|
| T008 | `DIAG-02 MISSING_REQUIRED violations at send are reported in ascending key-name order` (follow-up, six required, unbound `MESSAGE` keys) | `checkRequiredKeys` in `ContextProducer` already sorts the key names (1a18ec6); the original two-key ordering test stays green without the sort in about half of JVM runs, because `Map.copyOf`'s iteration order depends on a per-JVM salt | `checkRequiredKeys` without `.sorted()`: the six violations come in the policy map's iteration order, ascending by chance in 1 of 720 orders |
| T009 | `KAF-05 a BatchMessageListener is not scoped: it runs in the current snapshot, and nothing is decoded or reported for its records` | Without T009's advice nothing is scoped, so the batch listener already runs in the current snapshot, with nothing decoded or reported | `ListenerScopeAdvice` without its `BatchMessageListener` check and scoping a `List` argument in its first record's snapshot: the batch listener sees `tenant=acme` |
| T009 | `KAF-03 listener methods other than onMessage with a record pass through unscoped` | Without T009's advice nothing is scoped, so `onPartitionsAssigned` already runs in the outer snapshot | `ListenerScopeAdvice` running invocations other than a record `onMessage` in `ContextSnapshot.EMPTY`: `onPartitionsAssigned` sees no `tenant` |
| T010 | `KAF-05 a batch @KafkaListener is not scoped, and nothing is decoded or reported for its records` | T009's advice already passes `BatchMessageListener` invocations through | Two changes, each shown red and reverted: (1) `ListenerScopeAdvice` without its `BatchMessageListener` check, scoping a `List` argument in its first record's snapshot: the batch listener sees `tenant`; (2) `ListenerScopeAdvice` without its `BatchMessageListener` check, building `snapshot(record)` for every record of a `List` argument and proceeding unscoped: one `INVALID_VALUE` is reported |
| T011 | `KAF-03 when rebuilding the failed record's snapshot throws, the error handler still runs, in the empty snapshot` | Before T011 the error handler runs undecorated on the consumer thread, which has no binding, so it runs and sees an empty snapshot | `ContextErrorHandler` rethrowing the rebuild's exception instead of falling back to `ContextSnapshot.EMPTY`: the application's handler is not called |
| T011 | `KAF-03 an Error thrown while rebuilding the failed record's snapshot propagates, and the error handling does not run` (D82) | Written after T011's code, whose `ContextErrorHandler` already falls back to the empty snapshot only for a `RuntimeException` | `ContextErrorHandler` with `catch (Throwable e)` instead of `RuntimeException`: the consumer does not stop (no `ConsumerStoppedEvent` with `Reason.ERROR`) |
| T011 | `KAF-03 a record whose value fails deserialization never has its header violations reported, and its error handling runs in its snapshot` (D82) | Written after T011's code, whose `rebuild(record)` already reports nothing, and the container throws `DeserializationException` before the listener's advice builds the snapshot | `rebuild(record)` reporting its violations: an `INVALID_VALUE` for `tenant` is reported |
| T011 | `KAF-03 a container with a transaction manager that is not Kafka-aware is transactional: it gets no error handler, and its AfterRollbackProcessor is decorated` (a plain `PlatformTransactionManager` set through the deprecated `setTransactionManager`) | Written after T011's code, whose `transactional()` already checks `getTransactionManager()` | `transactional()` without the `getTransactionManager()` clause: the container's handler is a `ContextErrorHandler` instead of `null` |
| T012 | `KAF-03 with trust-headers=false, error handling of a record whose value fails deserialization reports no UNTRUSTED_HEADER` (in `ListenerErrorHandlingTest`) | Written after T011's and T012's code: the rebuild reports nothing, in the untrusted branch of `KafkaContext` too | The untrusted branch of `KafkaContext` reporting regardless of the report flag: an `UNTRUSTED_HEADER` is reported |
| T012 | `KAF-04 with trust-headers=false the snapshot is empty and one UNTRUSTED_HEADER is reported per policy wire header name present, however often and whatever its value`, an added record with only `tenant-id` | Written after T012's code, which matches header names exactly | Header names matched ignoring case: the record gives an `UNTRUSTED_HEADER` for `tenant` |

Outside 007: the Spring module's `ContextClientInterceptorTest` (`DIAG-02 several unbound required keys are each reported in ascending
key-name order, and the exception carries the first`, around line 704) has the same weakness, with only the keys `region` and `tenant`. It
will be fixed on a separate `test/...` branch, not in this feature. Likewise the Javadoc warning on `BoundrailContextAutoConfiguration`,
which predates 007, is fixed on a `docs/javadoc-warnings` branch before M7; T015 fixes only the warnings 007 introduced.

## TDD Exceptions

Approved by the user on 2026-10-18; its content follows KAF-06 as revised by D79 and by D81 (the re-send item, accepted by the user on
2026-10-21). T015's row was approved by the user on 2026-10-26.

| Task | What | Verified by |
|---|---|---|
| T015 | Close-out fixes from T014's review (accepted, and approved as a TDD exception, by the user on 2026-10-26): the `KafkaAutoConfigurationTest` back-off test renamed to `KAF-04 without Spring Kafka, or without the Spring module's auto-configuration, the Kafka auto-configuration backs off and the context starts` (a name, no behaviour); the Javadoc warnings 007 introduced, on `BoundrailKafkaAutoConfiguration`'s default constructor and `AdapterSupport`'s missing `@param` tags; the `KafkaContext` Javadoc's not-covered item for containers that are neither beans nor registered with the `KafkaListenerEndpointRegistry` (KAF-06); `forRecord`'s Javadoc and contracts/kafka-api.md saying scope listeners may observe enter and exit pairs (KAF-05, D78, Q9). No behaviour a test can fail on | Review of T015, and `./gradlew :boundrail-context-kafka:javadoc :boundrail-context-spring:javadoc` reporting no warning for those classes |
| T013 | The Javadoc of `KafkaContext` required by KAF-06 (D79, D81, D82): batch listeners are not scoped and `forRecord` gives each record's snapshot (KAF-05); the sends covered (producers the `ProducerFactory` of a `KafkaTemplate` bean creates, including `execute(ProducerCallback)`, non-bean templates sharing the factory and producers created directly from it) and the gaps (a `ProducerFactory` that is not a `DefaultKafkaProducerFactory`, producers created and cached before the hook was added) (KAF-01); the deadline is neither sent nor applied at send (KAF-01, D75); the headers are written into the application's `ProducerRecord`, so re-sending the same record instance is not supported: the send can throw `IllegalStateException` when it adds a header, and headers written before an `encode` that throws can stay on the record (KAF-01, D81); the containers covered (those created by listener container factory beans), `RecordInterceptor`s outside the scope, and the listener of a container whose advice chain the application replaces not scoped, with no promise for `@KafkaListener` containers (KAF-03); error handling in the failed record's snapshot, except containers created or registered after startup, containers that are neither beans nor registered with the `KafkaListenerEndpointRegistry` (added in T015), `MessageListenerContainer` beans whose phase is lower than `DEFAULT_PHASE`, `handleOtherException` and the batch paths, and a record whose decoding throws, with a required `MESSAGE` key in `fail` mode, cannot be dead-lettered and is redelivered (KAF-03); a record whose deserialization fails never has its header violations reported, though its error handling still runs in its snapshot (KAF-03, D82); only the listener invocation and error handling are scoped, so code completing a returned `CompletableFuture` or `Mono` runs with its own thread's context (KAF-03). Documentation, with no behaviour a test can fail on; the behaviour is tested in T004 – T012 | Review of T013, and inspection in T014 against KAF-06's wording |

T001 is a spike, not an exception: no code from it is kept. Build changes (`-kafka`'s dependencies, the catalog alias, `integration-tests`'
`spring-boot-kafka`) and topology test code are part of the red step of the task whose tests need them (T003, T006, T010). The
`AutoConfiguration.imports` file is verified by a failing test in T003.

## Project Structure

### Documentation (this feature)

```text
specs/007-kafka/
├── spec.md
├── plan.md                # this file
├── tasks.md
└── contracts/kafka-api.md
```

### Source Code (repository root)

```text
boundrail-context-spring/src/main/java/io/github/boundrail/context/spring/
├── BoundrailContextAutoConfiguration.java (AdapterSupport bean)          # T002
└── internal/AdapterSupport.java                                         # T002, T015
boundrail-context-spring/src/test/java/io/github/boundrail/context/spring/
└── internal/AdapterSupportTest.java                                     # T002
boundrail-context-kafka/build.gradle.kts                                 # T003, T006
boundrail-context-kafka/src/main/resources/META-INF/spring/
└── org.springframework.boot.autoconfigure.AutoConfiguration.imports    # T003
boundrail-context-kafka/src/main/java/io/github/boundrail/context/kafka/
├── BoundrailKafkaAutoConfiguration.java                                 # T003, T006, T010, T011, T015
├── BoundrailKafkaProperties.java                                        # T003
├── KafkaContext.java (forRecord, rebuild; Javadoc KAF-06)               # T003 – T005, T011 – T013, T015
├── TemplatePostProcessor.java (package-private)                         # T006
├── ContextProducerPostProcessor.java, ContextProducer.java              # T006 – T008
├── ListenerScopeAdvice.java (package-private)                           # T009
├── ListenerContainerFactoryPostProcessor.java (package-private)         # T010
├── ContextErrorHandler.java, ContextAfterRollbackProcessor.java         # T011
└── ErrorHandlingInstaller.java (SmartLifecycle)                         # T011
boundrail-context-kafka/src/test/java/io/github/boundrail/context/kafka/
├── KafkaAutoConfigurationTest.java                                      # T003, T015
├── RecordSnapshotTest.java (T004, T005)  RecordTrustTest.java (T012)
├── ProducerHeadersTest.java  ProducerRequiredKeysTest.java              # T006 – T008
├── ListenerScopeAdviceTest.java  ListenerScopeTest.java                 # T009, T010
├── ListenerErrorHandlingTest.java                                       # T011
└── support/KafkaBroker.java  support/TestPolicies.java                  # T004, T006
gradle/libs.versions.toml (spring-boot-kafka, no version)                # T006
integration-tests/build.gradle.kts (spring-boot-kafka)                   # T010
integration-tests/src/test/java/io/github/boundrail/context/it/
├── DeadlinePropagationIT.java (IT-3.8)  AuthorityPropagationIT.java (IT-4.9)
└── topology/Topology.java  EdgeApp.java  BillingApp.java  ObservedContext.java
```

**Structure Decision**: `KafkaContext` and `BoundrailKafkaProperties` are the only public types in `-kafka`'s contract; `AdapterSupport` is
the only public type added to `-spring`, in a package no contract declares. `BoundrailKafkaAutoConfiguration` is public because Spring Boot
requires it, but auto-configuration classes are not part of any contract (D81). Everything else is package-private.

## Requirement Coverage

| Requirement or scenario | Tasks |
|---|---|
| CORE-17 | T004 (`forRecord(null)`) |
| KAF-01 | T006 (headers, UTF-8, CR/LF, no `Authorization`/`Boundrail-Deadline-Ms`, expired deadline, every send method, subclass, composition, sending thread, encode exception), T007 (application-set header, WIRE-04), T008 (required keys, both modes, POL-08, transactions), T010 (a listener's send), IT-3.8, IT-4.9 |
| KAF-02 | T004 (trusted decoding, ignored headers, exact names, empty, decode failures, other exceptions, reporting context, DIAG-09), T005 (repeat, `null`, WIRE-04, UTF-8), T009 (invocation: replace, restore, empty, consecutive, exceptions, redelivery), T010 (the container's context reports), IT-3.8, IT-4.9 |
| KAF-03 | T009 (around-style advice, on the ScopedValue backend of `-kafka`'s unit tests), T010 (every factory bean, Boot's, `createContainer` bean, outermost, application advice inside, `RecordInterceptor`s outside; both backends through IT-3.8 on `integrationTestJdk21` and `integrationTestJdk25`), T011 (both decorators, the installer, a decorated `DefaultErrorHandler` only where none is set and never on transactional containers, empty-snapshot fallback (guard), no double reporting) |
| KAF-04 | T003 (activation, no off switch, binding, startup failure with lazy initialization), T012 (untrusted path, `AUTHORITY`/`DEADLINE` never bound), IT-3.8, IT-4.9 (default trust), T015 (the back-off test named for both conditions; TDD exception) |
| KAF-05 | T003 (bean, no public constructor), T004 (`forRecord` does not enter, reports per call), T015 (`forRecord`'s Javadoc and the contract: enter and exit pairs, Q9; TDD exception), T009 (batch listener passes through), T010 (batch `@KafkaListener`, guard) |
| KAF-06 | T013 (Javadoc: TDD exception), T015 (the not-covered item for containers that are neither beans nor registered; TDD exception) |
| IT-3.8 | T010 |
| IT-4.9 | T010 (ID-06's `MESSAGE` clause) |
| SC-001 – SC-003 | T014 |

## Risks

- **Coverage gaps D79 accepts.** A `ProducerFactory` that is not a `DefaultKafkaProducerFactory` is silently uncovered (its
  `addPostProcessor` does nothing), and so is a producer created and cached before the hook was added; containers created or registered
  after startup keep the advice but get no error-handling decorator. KAF-06 states all three; no test pins them.
- **Decode exceptions loop in `fail` mode (D79).** A record whose decoding throws, with a required `MESSAGE` key in `fail` mode, is redelivered
  because its dead-letter send fails in the empty snapshot. Accepted by the user; KAF-06 states it.
- **Installer ordering.** The installer must run before every container starts: phase `DEFAULT_PHASE - 1` and the registry looked up in
  `start()`, never injected (injecting it started the registry first in T001). T011's tests use Boot's factory, an application factory, a
  `createContainer` bean and a transactional container.
- **Advice-chain edges (finding 4).** An application `ContainerCustomizer` that replaces the advice chain removes the scope from containers
  whose listener is set afterwards; the library cannot detect it without reflection. KAF-06 documents it (D79).
- **Backend coverage.** `-kafka`'s unit tests load core's multi-release JAR on the JDK 25 toolchain, so they run on the ScopedValue backend
  (T001 item 4); nothing runs them on ThreadLocal. The ThreadLocal backend of the Kafka adapters is exercised only by IT-3.8 and IT-4.9 on
  `integrationTestJdk21` (the forced ThreadLocal run is limited to RuntimeConformanceIT). T009 and T010 therefore rely on IT-3.8 and IT-4.9
  on JDK 21 for the ThreadLocal side, and T014 checks both runs.
- **Docker in unit tests.** `:boundrail-context-kafka:test` needs Docker, as `integration-tests` already does; one broker per test JVM,
  unique topics and group ids per test, records read back with a timeout, so tests do not interfere.
- **Shared topology.** After T010 every service has Boot's Kafka auto-configuration and billing a listener; TopologySmokeIT, IT-1 – IT-4 must
  stay green (T010 runs every suite). Billing's 4-second wait makes IT-3.8 and IT-4.9 slow and serial; the scenario timeout (30 seconds)
  allows for it. `auto-offset-reset=earliest` makes delivery safe without a `NewTopic` (T001 item 5).
- **Two scope entries per record.** The temporary scope that builds the snapshot (D78) and the invocation's scope: `ScopeListener`s (the MDC
  bridge) run twice per record and once per `forRecord`; correct, slightly more work.

## Complexity Tracking

No constitution violations to justify.
