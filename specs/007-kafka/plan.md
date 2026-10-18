# Implementation Plan: Kafka

**Branch**: `007-kafka` | **Date**: 2026-10-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/007-kafka/spec.md` (Status "Ready for planning"), contract
[contracts/kafka-api.md](contracts/kafka-api.md), decisions D70 – D77 in [design.md](../../docs/design.md) (user-decided 2026-10-18), roadmap
[M6](../../docs/roadmap.md#m6--kafka), the merged code of 002 – 006 (`main` at d6243fc), and the Spring Kafka 4.1.1 and Spring Boot 4.1.1
sources from the Gradle cache (read, not run)

**Status**: Approved by the user on 2026-10-18: plan, tasks, the TDD exception row for the KAF-06 Javadoc (T013) and the expected guard in
[Guard Tests](#guard-tests); Q5 – Q7 ([Spec Gaps](#spec-gaps)) are settled (D78). T001, the spike, runs first; its outcome goes back to the
user before any implementation task, as 006's T001 and B2 did. Q1 – Q4 are expected to be settled on that outcome.

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
  - the producer hook on every `KafkaTemplate` bean (KAF-01, D71), mechanism per T001 and Q1;
  - `ListenerScopeAdvice`, an around-style `MethodInterceptor` placed first in the `ContainerProperties` advice chain of every
    `AbstractKafkaListenerContainerFactory` bean (KAF-02, KAF-03, D72), and the error-handling hook of D73, mechanism per T001 and Q3;
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
(T002 – T012), one TDD-exception task (T013) and a close task (T014)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|---|---|---|
| I Scope-only | Pass, pending T001 | Each record-mode invocation runs as `ContextSnapshot.EMPTY.call(() -> binding.call(invocation::proceed))`; nothing is set or cleared. D73's error handling must also be around-style; if T001 finds no such hook, the fallback is to document it (Q3) rather than use a set/restore hook |
| II Declared propagation | Pass | Only keys that propagate to `MESSAGE` are written (POL-04) or read; application keys need a codec and wire name for `MESSAGE` |
| III Secure by default | Pass | `AUTHORITY` and `DEADLINE` excluded in both directions regardless of `trust-headers`; untrusted headers reported, never bound (KAF-04); violations never hold values (DIAG-09) |
| IV Declared, never guessed | Pass | `MESSAGE` is detached: no deadline sent or applied at send (D75); the listener scope replaces the consumer thread's snapshot (KAF-02) |
| V Evidence only | Pass | Violations carry kind, key, `MESSAGE`, an empty destination and DIAG-04's thread evidence; no topic is added to `Violation` (D74) |
| VI Interoperate | Pass, pending Q1, Q2 | The hooks compose with the application's template interceptor, advice and error handler instead of replacing them; T001 confirms no reflection is needed |
| VII Spec-first, test-first | Pass, pending approval | Every task is one red-green cycle; IT-3.8 and IT-4.9 are T010's failing tests; the TDD exception row (KAF-06 Javadoc) and the guard row await approval; Q1 – Q7 await the user |
| VIII Simplicity | Pass | No switch to turn adapters off (D77); one internal interface for D70; no batch scoping (KAF-05); no new public type beyond `KafkaContext` and the properties record |
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
  `@ConditionalOnBean(AdapterSupport.class)` (the Spring module's auto-configuration is active; Q5), `@EnableConfigurationProperties(
  BoundrailKafkaProperties.class)`; registered in `boundrail-context-kafka/src/main/resources/META-INF/spring/
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
  current after `forRecord` returns, but `ScopeListener`s such as the MDC bridge see a nested enter/exit pair on the caller's thread (Q7).

### Producer (KAF-01, D71)

Assuming T001 confirms finding 2 and the user picks option (a) of Q1; otherwise T006's green step is rewritten before it starts.

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

### Listener scope (KAF-02, KAF-03, D72)

- **`ListenerScopeAdvice` (T009).** A package-private `MethodInterceptor` holding the `KafkaContext`. If `invocation.getThis()` is a
  `BatchMessageListener`, the method is not `onMessage`, or the first argument is not a `ConsumerRecord`, it returns `invocation.proceed()`
  (KAF-05; other listener methods). Otherwise it builds `snapshot(record)` (a non-`IllegalArgumentException` from `decode` propagates, so the
  listener is not invoked) and returns `ContextSnapshot.EMPTY.call(() -> binding.call(invocation::proceed))`, always entering a scope, also for
  an empty snapshot (D73), so the consumer thread's snapshot is replaced (CORE-10) and restored, and the listener's exception propagates
  unchanged (CORE-08). `invocation.proceed()` declares `Throwable`; `ContextCallable<R, X>` carries it.
- **Wiring (T010).** A `static` bean post-processor `ListenerContainerFactoryPostProcessor` sets, on every
  `AbstractKafkaListenerContainerFactory` bean after initialization, `getContainerProperties().setAdviceChain(advice, existing...)`, unless the
  first element already is the library's advice. The application's advice set in its `@Bean` method is in `existing`, so it runs inside.
- **Error handling (T011, D73).** Mechanism per T001 and Q3; the recommended decorator builds the failed record's snapshot without reporting
  (the delivery already reported its violations, so counts are not doubled) and runs `handleOne`/`handleRemaining` in it; per Q4, an empty
  snapshot when building throws.

### Integration tests

- **Topology (T010).**
  - `Topology.startService` adds `spring.kafka.bootstrap-servers=<broker>` for every service, and for billing
    `spring.kafka.consumer.group-id=billing` and `spring.kafka.consumer.auto-offset-reset=earliest`; billing also declares
    `NewTopic("orders.events")` if T001 item 5 shows it is needed.
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

Open; each blocks the tasks named until the user answers. Q1 – Q4 are expected to be settled on T001's outcome. The spec-creator writes the
answers into spec.md and design.md.

| Gap | Status | Question | Recommendation | Tasks |
|---|---|---|---|---|
| Q1 | Open | Which producer hook meets D71? `KafkaTemplate` 4.1.1 has no getter for its interceptor (finding 1), so the template's own interceptor cannot compose with an application's without reflection | (a) A `ProducerPostProcessor` on the `ProducerFactory` of each `KafkaTemplate` bean (finding 2): composes, throws from the send call, runs on the sending thread, covers `executeInTransaction`. It also covers `execute(ProducerCallback)`, templates that are not beans but share that factory, and producers created from it directly, so KAF-01's coverage sentence and KAF-06's "not covered" list change to "every send through a producer created by the `ProducerFactory` of a `KafkaTemplate` bean". Rejected: (b) a template proxy, which misses sends inside `executeInTransaction` (finding 3); (c) `setProducerInterceptor`, which replaces the application's interceptor | T006 – T008, T013 |
| Q2 | Open | KAF-03 says interceptors the application configured run inside the library's scope, but the advice chain wraps only the listener: `RecordInterceptor.intercept`, `success`, `failure` and `afterRecord` run outside (finding 5). Also: a `ContainerCustomizer` or `ContainerPostProcessor` that calls `setAdviceChain` removes the library's advice, and an already-proxied listener gets the chain after its own advisors (finding 4) | Revise KAF-03: the application's advice in the factory's chain runs inside the scope; `RecordInterceptor`s, which are set/restore hooks, run outside it; containers whose advice chain the application replaces after the factory configured it are not covered. KAF-06 states both | T010, T013 |
| Q3 | Open | Which around-style hook runs the error handler inside the record's snapshot (D73)? The advice cannot (finding 5); the factory has no getter for its error handler (finding 6) | If T001 confirms it: a `CommonErrorHandler` decorator installed on each container before it starts, by a `SmartLifecycle` over the endpoint registry and the container beans (an explicit `DefaultErrorHandler` where none is set). Not covered, and stated in KAF-06: containers registered after startup, `handleOtherException`, `handleBatch` (KAF-05), and transactional containers' `AfterRollbackProcessor`. Otherwise the D73 fallback: KAF-06 says error handling runs outside the scope | T011, T013 |
| Q4 | Open | Which failures does D73 cover, and with which snapshot? The error handler also handles records whose listener was never invoked: a deserialization failure, and a decode exception from KAF-02 (WIRE-05) | The decorator runs every `handleOne`/`handleRemaining` in the failed record's snapshot, built without reporting again (the delivery already reported); when building it throws, in the empty snapshot, so the error handler can still recover the record | T011 |
| Q5 | Open | D77's activation conditions (Spring Kafka on the classpath, the Spring module's auto-configuration active) are in design.md only, not in spec.md. Without the Spring module's auto-configuration there is no `AdapterSupport` | Add them to spec.md (Assumptions or KAF-05) and test both: excluding `BoundrailContextAutoConfiguration`, or filtering `KafkaTemplate` from the class path, creates no Kafka bean and the context starts. Keep `spring-kafka` as an `implementation` dependency of `-kafka` | T003 |
| Q6 | Open | Two public names the contract leaves open: the properties record (contract: "the properties record that binds `boundrail.context.kafka.*`") and the `spring.internal` type (D70 says "public classes") | `io.github.boundrail.context.kafka.BoundrailKafkaProperties`, mirroring `BoundrailContextProperties`; `io.github.boundrail.context.spring.internal.AdapterSupport`, an interface implemented in the Spring auto-configuration (D70's "classes" read as "types") | T002, T003 |
| Q7 | Open | `forRecord` returns the snapshot "without entering it", but core has no public snapshot factory: building it enters a temporary scope on the caller's thread, which `ScopeListener`s such as the MDC bridge observe as an enter/exit pair (nothing stays current) | Accept: "without entering it" means the snapshot is not current when `forRecord` returns; record it in spec.md's KAF-05. A core snapshot factory would change 002's contract, outside M6 | T004 |

## Guard Tests

These tests already pass before the task that introduces them. As in 002 – 006, each red is shown against a named temporary change, which
is reverted before the commit. They are not TDD exceptions; listed here for the user's approval.

A test that is green before its task is accepted as a guard when its red is shown against a named temporary change to the finished code, which
is then reverted; the leader reports it in the task report, the planner adds it to this table, and the reviewer checks it.

A negative assertion that sits in the same test as a positive assertion that is red before its task is not a guard, and is not listed (for
example, "no `Authorization` or `Boundrail-Deadline-Ms` header" sits with "a header per bound `MESSAGE` key" in T006, and "keys that do not
propagate to `MESSAGE` are not checked" with the `MISSING_REQUIRED` assertions in T008).

| Task | Scenario or test | Why green first | Red shown against (temporary, reverted) |
|---|---|---|---|
| T010 | `KAF-05 a batch @KafkaListener is not scoped, and nothing is decoded or reported for its records` | T009's advice already passes `BatchMessageListener` invocations through | `ListenerScopeAdvice` without its `BatchMessageListener` check and scoping a `List` argument in its first record's snapshot: the batch listener sees `tenant` and a repeated header is reported |

## TDD Exceptions

For the user's approval.

| Task | What | Verified by |
|---|---|---|
| T013 | The Javadoc of `KafkaContext` required by KAF-06: batch listeners are not scoped and `forRecord` gives each record's snapshot (KAF-05); which sends are covered and which are not (KAF-01, as settled by Q1); which listener containers are covered (KAF-03, as settled by Q2); the deadline is neither sent nor applied at send (KAF-01, D75); only the listener invocation and the container's error handling are scoped, so code completing a returned `CompletableFuture` or `Mono` runs with its own thread's context (KAF-03, as settled by Q3). Documentation, with no behaviour a test can fail on; the behaviour is tested in T004 – T012 | Review of T013, and inspection in T014 against KAF-06's wording |

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
└── internal/AdapterSupport.java                                         # T002
boundrail-context-spring/src/test/java/io/github/boundrail/context/spring/
└── internal/AdapterSupportTest.java                                     # T002
boundrail-context-kafka/build.gradle.kts                                 # T003, T006
boundrail-context-kafka/src/main/resources/META-INF/spring/
└── org.springframework.boot.autoconfigure.AutoConfiguration.imports    # T003
boundrail-context-kafka/src/main/java/io/github/boundrail/context/kafka/
├── BoundrailKafkaAutoConfiguration.java                                 # T003, T006, T010, T011
├── BoundrailKafkaProperties.java                                        # T003
├── KafkaContext.java (forRecord; Javadoc KAF-06)                        # T003 – T005, T012, T013
├── TemplatePostProcessor.java (package-private, per Q1)                 # T006
├── ContextProducerPostProcessor.java, ContextProducer.java (per Q1)     # T006 – T008
├── ListenerScopeAdvice.java (package-private)                           # T009
├── ListenerContainerFactoryPostProcessor.java (package-private)         # T010
└── error-handling hook (per Q3)                                         # T011
boundrail-context-kafka/src/test/java/io/github/boundrail/context/kafka/
├── KafkaAutoConfigurationTest.java                                      # T003
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

**Structure Decision**: `KafkaContext` and `BoundrailKafkaProperties` are the only public types in `-kafka`; `AdapterSupport` is the only
public type added to `-spring`, in a package no contract declares. Everything else is package-private.

## Requirement Coverage

| Requirement or scenario | Tasks |
|---|---|
| CORE-17 | T004 (`forRecord(null)`) |
| KAF-01 | T006 (headers, UTF-8, CR/LF, no `Authorization`/`Boundrail-Deadline-Ms`, expired deadline, every send method, subclass, composition, sending thread, encode exception), T007 (application-set header, WIRE-04), T008 (required keys, both modes, POL-08, transactions), T010 (a listener's send), IT-3.8, IT-4.9 |
| KAF-02 | T004 (trusted decoding, ignored headers, exact names, empty, decode failures, other exceptions, reporting context, DIAG-09), T005 (repeat, `null`, WIRE-04, UTF-8), T009 (invocation: replace, restore, empty, consecutive, exceptions, redelivery), T010 (the container's context reports), IT-3.8, IT-4.9 |
| KAF-03 | T009 (around-style advice), T010 (every factory bean, Boot's, `createContainer` bean, outermost, composition; ScopedValue backend by IT-3.8 on `integrationTestJdk25`), T011 (error handling, per Q3) |
| KAF-04 | T003 (binding, startup failure with lazy initialization), T012 (untrusted path, `AUTHORITY`/`DEADLINE` never bound), IT-3.8, IT-4.9 (default trust) |
| KAF-05 | T003 (bean, no public constructor), T004 (`forRecord` does not enter, reports per call), T009 (batch listener passes through), T010 (batch `@KafkaListener`, guard) |
| KAF-06 | T013 (Javadoc: TDD exception) |
| IT-3.8 | T010 |
| IT-4.9 | T010 (ID-06's `MESSAGE` clause) |
| SC-001 – SC-003 | T014 |

## Risks

- **The hooks depend on T001 (Q1 – Q4).** If option (a) of Q1 is refused, T006 – T008's green steps change; if Q3's decorator proves
  unreliable, T011 is replaced by the D73 fallback and KAF-03 and KAF-06 are revised first. The task list keeps those tasks separate so the
  rest does not move.
- **Coverage wider than the spec (Q1 (a)).** The producer wrapper also covers sends the spec lists as not covered; no test asserts their
  absence, and the spec wording is revised with the user.
- **Advice-chain edges (finding 4).** An application `ContainerCustomizer` that replaces the advice chain silently removes the scope; the
  library cannot detect it without reflection. KAF-06 documents it (Q2).
- **ScopedValue proof only in `integration-tests`.** `-kafka`'s unit tests may run on the ThreadLocal backend (T001 item 4); KAF-03's
  around-style requirement is proven by IT-3.8 on `integrationTestJdk25`, and the review checks that only `ContextSnapshot.call` scopes.
- **Docker in unit tests.** `:boundrail-context-kafka:test` needs Docker, as `integration-tests` already does; one broker per test JVM,
  unique topics and group ids per test, records read back with a timeout, so tests do not interfere.
- **Shared topology.** After T010 every service has Boot's Kafka auto-configuration and billing a listener; TopologySmokeIT, IT-1 – IT-4 must
  stay green (T010 runs every suite). Billing's 4-second wait makes IT-3.8 and IT-4.9 slow and serial; the scenario timeout (30 seconds)
  allows for it. A consumer assigned after the first send is handled by `auto-offset-reset=earliest` (and `NewTopic` if T001 item 5 needs it).
- **Two scope entries per record.** `ScopeListener`s (the MDC bridge) run twice per record and twice per `forRecord`; correct, slightly more
  work (Q7).

## Complexity Tracking

No constitution violations to justify.
