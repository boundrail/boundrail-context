# Feature Specification: Kafka

**Feature Branch**: `007-kafka`

**Created**: 2026-09-28

**Status**: Ready for planning

**Milestone**: M6

**Input**: Roadmap milestone [M6](../../docs/roadmap.md#m6--kafka).

API signatures: [contracts/kafka-api.md](contracts/kafka-api.md); `ContextSnapshot` and `ContextPolicy` are defined in
[002-core-runtime](../002-core-runtime/contracts/core-api.md), and `Violation` and `ContextViolationException` in
[003-http-trust](../003-http-trust/contracts/http-api.md). Test topology: [001-foundation](../001-foundation/spec.md#test-topology).

## User Scenarios & Testing *(mandatory)*

In DeadlinePropagationIT and AuthorityPropagationIT, edge publishes events with the `KafkaTemplate` Spring Boot auto-configures, to the
topic `orders.events`, with the scenario's marker in the record's value. Billing consumes `orders.events` with a record-mode `@KafkaListener`
on the listener container factory Spring Boot auto-configures, with the default `kafka.trust-headers=true`. Every service is configured
with `spring.kafka.bootstrap-servers` set to the Testcontainers broker. When billing's listener starts processing a record, it records to
`ObservedContext`, keyed by the marker, the names of the record's headers and the deadline, `tenant`, initiator and authority subject it
finds bound. In IT-3.8, "edge" is DeadlinePropagationIT's own edge, started with `deadline.default-inbound=500ms` (DESIGN D57), and
billing's listener waits until at least 4 seconds after the record's timestamp before it starts processing (DESIGN D77).

### User Story 1 - Context travels with messages under detached semantics (Priority: P1)

Records produced during a request carry the keys declared for `MESSAGE`. Each record-mode listener invocation runs in exactly the context its
record carried, which never includes the deadline or the authority, so an event processed long after the request still succeeds and knows who
caused it.

**Why this priority**: The messaging half of the deadline and identity stories; without it, the acceptance suites cannot all be green.

**Independent Test**: DeadlinePropagationIT IT-3.8 and AuthorityPropagationIT IT-4.9 against the Testcontainers broker.

**Acceptance Scenarios**:

1. **IT-3.8 Kafka event outlives the request.** Given a public request authenticated as alice to edge, which binds edge's 500ms default
   deadline, when the edge handler publishes an event to `orders.events` and billing's listener starts processing it at least 4 seconds
   after it was published, then the listener returns normally and records exactly one observation for the event, with no deadline,
   `tenant=acme` and `initiator=user:alice`, and the record has no `Boundrail-Deadline-Ms` header. *(KAF-01, KAF-02, KAF-03, KAF-04)*
2. **IT-4.9 Messages carry the initiator, never the authority.** When the edge handler of a public request authenticated as alice
   publishes an event to `orders.events`, then billing's listener observes `initiator=user:alice` and no authority, and the record has a
   `Boundrail-Initiator` header and no `Authorization` header. *(ID-06, KAF-01, KAF-02, KAF-04)*

---

### User Story 2 - Header trust is configurable (Priority: P2)

Record headers are trusted by default, because the broker is internal (DESIGN D25). A service that consumes from a broker it does not trust
turns trust off: then nothing is bound from headers, and each context header present is reported.

**Why this priority**: Needed for brokers shared with untrusted producers; the default covers the internal-broker case first.

**Independent Test**: Unit tests for KAF-04.

**Acceptance Scenarios**: Covered by unit tests whose display names start with `KAF-04`. No acceptance-suite scenario.

---

### User Story 3 - Manual scoping for batch listeners (Priority: P3)

Batch listeners are not scoped automatically in v0.1; a helper returns the snapshot a record would have been bound with.

**Why this priority**: A small escape hatch until automatic batch support is designed.

**Independent Test**: Unit tests for KAF-05.

**Acceptance Scenarios**: Covered by unit tests whose display names start with `KAF-05`. No acceptance-suite scenario.

---

### Edge Cases

- Consecutive records on the same consumer thread: KAF-02 (each listener invocation runs in exactly its own record's snapshot, replacing
  the current one; nothing leaks).
- A record with no context headers: KAF-02 (the listener runs in an empty snapshot, replacing whatever is current).
- The listener throws: KAF-02 (the exception propagates unchanged and the previous snapshot is restored); the container's error handling for
  that record, through its decorated `CommonErrorHandler` or, on a transactional container, its `AfterRollbackProcessor`, runs inside the
  record's snapshot, so a `DeadLetterPublishingRecoverer` publishing it still has a required `MESSAGE` key bound (KAF-03).
- A record whose decoding throws something other than `IllegalArgumentException` (KAF-02) goes to a dead-letter recoverer, in `fail` mode,
  with a required `MESSAGE` key: KAF-03 (the error handling runs in the empty snapshot, the dead-letter send fails, and the record is
  redelivered).
- A container created or registered after startup: KAF-03, KAF-06 (its error handling runs outside the record's snapshot). A failure that
  reaches `handleOtherException`, which has no record, or a batch error handling path: KAF-03, KAF-06 (not scoped).
- A record redelivered after an error: KAF-02 (each delivery builds its snapshot again and reports its violations again).
- The listener returns a `CompletableFuture` or a `Mono`: KAF-06 (only the invocation is scoped; code that completes the result runs with
  its own thread's context).
- `kafka.trust-headers=false`: KAF-04 (nothing is bound, and each policy wire header present is reported once).
- A trusted record repeats `Tenant-Id`: KAF-02 (unbound, one `INVALID_VALUE`).
- A trusted record's `Tenant-Id` has a `null` value: KAF-02 (unbound, one `INVALID_VALUE`).
- A trusted header value that is not well-formed UTF-8: KAF-02 (unbound, one `INVALID_VALUE`); one that is also too long: KAF-02, WIRE-04
  (reported once, as `OVERSIZED_VALUE`).
- A trusted record carries `Boundrail-Deadline-Ms`, `Authorization` or `Priority` (a key that does not propagate to `MESSAGE`): KAF-02,
  KAF-04 (ignored, nothing reported). An untrusted one: KAF-04 (`Boundrail-Deadline-Ms` and `Priority` reported, `Authorization` not,
  because it is no policy wire name).
- An application codec's `decode` throws something other than `IllegalArgumentException`: KAF-02, WIRE-05 (it propagates as the listener's
  failure; the listener is not invoked; no violation).
- A required key is unbound when a record is sent, in `fail` mode: KAF-01 (`ContextViolationException` from the send call; the record never
  reaches the producer). In `report` mode: KAF-01 (sent without the header).
- A required key is unbound, but application code set its wire header on the record: KAF-01, POL-08 (still `MISSING_REQUIRED`; the check
  reads the context, not the headers).
- Application code sets `Tenant-Id` or `Authorization` on a record: KAF-01 (left unchanged; the context value is not encoded).
- An encoded value contains CR or LF: KAF-01 (written; WIRE-08 applies only to HTTP headers). An encoded value longer than
  `wire.max-value-length`: KAF-01 (not written, one `OVERSIZED_VALUE`).
- The deadline has expired when a record is sent: KAF-01 (the record is sent without it; the deadline is neither propagated nor applied).
- A listener sends a record: KAF-01 (the record carries the consumed record's `MESSAGE` keys, which are bound in the listener's scope).
- A send through `KafkaTemplate.execute(ProducerCallback)`, through a `KafkaTemplate` that is not a bean but shares the `ProducerFactory` of
  a template bean, or through a producer created directly from that factory: KAF-01 (covered).
- A `KafkaTemplate` bean whose `ProducerFactory` is not a `DefaultKafkaProducerFactory`, or a producer that the factory created and cached
  before the hook was added: KAF-01, KAF-06 (not covered).
- The application sends the same `ProducerRecord` instance a second time: KAF-01, KAF-06 (outside the guarantee; the send may throw
  `IllegalStateException` when it adds a header, and an `encode` that throws may leave the headers written before it on the record).
- A listener container that no listener container factory bean created: KAF-03, KAF-06 (its listener is not scoped).
- The application replaces a container's advice chain, for example with a `ContainerCustomizer` that calls `setAdviceChain`: KAF-03, KAF-06
  (not covered). The application adds advice to a factory's advice chain: KAF-03 (it runs inside the library's scope).
- An application `RecordInterceptor`: KAF-03 (`intercept`, `success`, `failure` and `afterRecord` run outside the listener scope).
- A batch listener: KAF-05 (not scoped; `KafkaContext.forRecord` gives each record's snapshot).
- `kafka.trust-headers` is not a boolean, with `spring.main.lazy-initialization=true`: KAF-04 (startup still fails).
- The application excludes `boundrail-context-spring`'s auto-configuration: KAF-04 (the Kafka auto-configuration backs off; records are
  sent and listeners invoked without the library, and startup does not fail).
- A `null` argument passed to the M6 contract: CORE-17.

## Requirements *(mandatory)*

### API conventions

**CORE-17** `[M6]` CORE-03 SHALL apply to the types and methods declared in [contracts/kafka-api.md](contracts/kafka-api.md) as it does to
`contracts/core-api.md`, with the same exceptions: a `null` argument SHALL throw `NullPointerException` at that call. Verified by unit
tests.

### Producer

**KAF-01** `[M6]` WHEN a record is sent through a producer that the `ProducerFactory` of a `KafkaTemplate` bean of the application context
creates, the Kafka module SHALL, on the sending thread and before the record is handed to the Kafka producer, perform steps 1 – 4 below. This covers
every send method of such a template, including sends of a `Message<?>`, `sendDefault` and sends inside `executeInTransaction`, and also
`execute(ProducerCallback)`, templates that are not beans but share that factory, and producers created directly from it. The Kafka module
SHALL do so through a `ProducerPostProcessor` added to the `ProducerFactory` of each `KafkaTemplate` bean. A `ProducerFactory` that is not a
`DefaultKafkaProducerFactory`, whose `addPostProcessor` does nothing, and a producer that the factory created and cached before the
post-processor was added are not covered (KAF-06; DESIGN D71, D79).

1. perform the required-key check (POL-08) for `MESSAGE`: report one `MISSING_REQUIRED` violation for each required key that propagates to
   `MESSAGE` and is unbound, in ascending order of key name (DIAG-02). In `fail` mode it SHALL then throw one `ContextViolationException`
   carrying the first, from the send call itself (from `executeInTransaction` for a send inside it), and SHALL NOT hand the record to the
   producer (DESIGN D71); in `report` mode the record
   is sent without the key;
2. write a record header for each key bound in the current snapshot filtered for `MESSAGE` (POL-04), named by the key's wire name and
   holding the UTF-8 bytes of its encoding (WIRE-01): the codec's `encode`, or the initiator's value (WIRE-02). `AUTHORITY` and `DEADLINE`
   do not propagate to `MESSAGE` (POL-06), so no `Authorization` or `Boundrail-Deadline-Ms` header SHALL be written (ID-06);
3. IF the record already carries a header with the wire name, compared exactly (WIRE-06), THEN leave it unchanged and not encode the context
   value, so no violation is reported for that key and its `encode` is not called, as OUT-04 does for HTTP (DESIGN D74);
4. IF an encoding is longer than `wire.max-value-length` (WIRE-04), THEN not write it, report one `OVERSIZED_VALUE` violation, and send the
   record. WIRE-08 SHALL NOT apply to record headers, which carry bytes, not HTTP field values (DESIGN D74).

Any exception thrown by a codec's `encode` SHALL propagate unchanged from the send call, no violation SHALL be reported for it (WIRE-05), and
the record SHALL NOT be handed to the producer. The violations of this requirement have the boundary `MESSAGE` and an empty destination
(DESIGN D74). The deadline SHALL NOT be checked when a record is sent: an expired deadline neither fails nor changes a send (DESIGN D75).
The headers are written into the application's own `ProducerRecord`, so sending the same `ProducerRecord` instance more than once is
outside this requirement: Kafka makes a record's headers read-only once it is sent, so a later send that adds a header MAY throw
`IllegalStateException`, and when an `encode` throws, the headers written before it MAY remain on the record (KAF-06; DESIGN D81).
Verified by IT-3.8, IT-4.9 and unit tests.

### Consumer

**KAF-02** `[M6]` WHEN a record is delivered to a record-mode listener that KAF-03 covers, that is a listener that receives one record per
invocation and is not a `BatchMessageListener`, the Kafka module SHALL build the record's snapshot as defined below and SHALL invoke the
listener inside it, replacing the snapshot current on the consumer thread (CORE-10), also when the record's snapshot is empty
(DESIGN D73). It SHALL restore the previous snapshot when the listener returns or throws, and an exception the listener throws SHALL
propagate unchanged (CORE-08). The record's snapshot SHALL contain exactly the bindings decoded from the record's headers, so nothing leaks
between consecutive records on one consumer thread.

WHERE `kafka.trust-headers=true` (KAF-04), for every key in the effective policy's `keys()` (POL-10) that propagates to `MESSAGE`, except
`AUTHORITY` and `DEADLINE`, whose wire header is present on the record (names compared exactly, WIRE-06), the module SHALL decode the
header as follows; headers of other keys, `Authorization`, and headers whose name is no policy wire name SHALL be ignored and not reported,
as IN-02's trusted path ignores them (DESIGN D74):

- IF the record carries the key's wire header more than once, THEN the key SHALL be left unbound, none of its values decoded, and one
  `INVALID_VALUE` violation reported;
- IF the header's value is `null`, THEN the key SHALL be left unbound and one `INVALID_VALUE` violation reported;
- WIRE-04 SHALL apply first, to the length of the value decoded as UTF-8 with malformed sequences replaced by U+FFFD; IF it is too long,
  THEN the key SHALL be left unbound and one `OVERSIZED_VALUE` violation reported;
- IF the value is not well-formed UTF-8, THEN the key SHALL be left unbound and one `INVALID_VALUE` violation reported;
- otherwise the string SHALL be decoded with the key's codec, or with `Initiator.of` for `Boundrail-Initiator` (WIRE-02). A decode failure
  leaves the key unbound and reports one `INVALID_VALUE` violation (WIRE-05). Any other exception from `decode` SHALL propagate unchanged,
  with no violation, and the listener SHALL NOT be invoked.

These violations have the boundary `MESSAGE` and an empty destination (DIAG-08), never contain the value (DIAG-09), and are reported through
the reporting of the application context that owns the listener container, with the evidence DIAG-04 requires (DIAG-03, DIAG-11).
Verified by IT-3.8, IT-4.9 and unit tests.

**KAF-03** `[M6]` The Kafka module SHALL scope each listener invocation around-style, running the listener as the operation of a scope (such
as `ContextSnapshot.call`), so that it works on the ScopedValue backend. Set/restore-style hooks, such as a `RecordInterceptor`, alone SHALL
NOT be the scoping mechanism, because they cannot drive the ScopedValue backend (DESIGN §5.6). It SHALL cover the listeners of every
listener container created by an `AbstractKafkaListenerContainerFactory` bean of the application context, including the factory Spring Boot
auto-configures, by adding the library's advice first in the `ContainerProperties` advice chain of each such factory, so the listeners of
the containers it creates, `@KafkaListener` containers included, are covered (DESIGN D72). The library's advice SHALL appear in each such
factory's advice chain exactly once, ahead of the application's advice. Advice the application adds to a factory's advice chain SHALL run
inside the library's scope. `RecordInterceptor`s run outside it, in `intercept`, `success`, `failure` and `afterRecord`. A container whose
advice chain the application replaces, for example with a `ContainerCustomizer` that calls `setAdviceChain`, is not covered
(KAF-06; DESIGN D79).

The container's handling of a record whose listener threw, including any recoverer such as `DeadLetterPublishingRecoverer`, SHALL run inside
that record's snapshot. The Kafka module SHALL decorate the `CommonErrorHandler` of each listener container, and the `AfterRollbackProcessor`
of each container that has a transaction manager, so that `handleOne`, `handleRemaining` and `process` run inside the snapshot of the failed
record, the first record they are given, and every other method is delegated unchanged. It SHALL install these decorators on every container
of the `KafkaListenerEndpointRegistry` and every `MessageListenerContainer` bean before those containers start. On a non-transactional
container that has no `CommonErrorHandler`, it SHALL set a decorated `DefaultErrorHandler`; on a transactional container it SHALL set none.
That snapshot SHALL be the failed record's, built as KAF-02 and KAF-04 define but without reporting its violations again; IF building it
throws, THEN the error handling SHALL run in the empty snapshot. So a record whose decoding throws something other than
`IllegalArgumentException` (KAF-02), sent to a dead-letter recoverer in `fail` mode with a required `MESSAGE` key, cannot be dead-lettered
and is redelivered. Not covered: containers created or registered after startup, `handleOtherException`, which has no record, and the batch
paths `handleBatch` and `processBatch` (KAF-05, KAF-06; DESIGN D73, D79). Verified by IT-3.8 in `integrationTestJdk25` (ScopedValue backend)
and unit tests.

**KAF-04** `[M6]` WHERE Spring Kafka and `boundrail-context-spring` are on the classpath, the Kafka module's auto-configuration SHALL be
active and SHALL apply KAF-01 – KAF-05. Both are dependencies of `boundrail-context-kafka`: Spring Kafka as an `implementation` dependency,
`boundrail-context-spring` as an `api` dependency (DESIGN D70). No property SHALL turn the Kafka adapters off (DESIGN D77, D78).
IF the Spring module's auto-configuration is not active, for example because the application excludes it, so that there is no
`AdapterSupport` bean, THEN the Kafka module's auto-configuration SHALL back off, applying none of KAF-01 – KAF-05, and SHALL NOT fail
startup (DESIGN D80).
WHERE `boundrail.context.kafka.trust-headers=true` (default), record headers SHALL be trusted, and decoded as KAF-02 defines (DESIGN D25).
WHERE it is `false`, the Kafka module SHALL bind no key from record headers, so the record's snapshot is empty, and
SHALL report one `UNTRUSTED_HEADER` violation per policy wire header name present on the record, however many times it appears and whatever
its value, `null` included, with the boundary `MESSAGE` and an empty destination. The policy wire header names are those IN-02 defines: the
wire names of every key in the effective policy's `keys()`, including `Boundrail-Deadline-Ms` and those of keys that do not propagate to
`MESSAGE`. `AUTHORITY` and `DEADLINE` SHALL never be bound from a record, regardless of configuration (ID-02). `kafka.trust-headers` SHALL
be bound as a boolean with Spring Boot's binding, which accepts `true`, `on`, `yes` and `1` and their opposites in any case; IF it is a value
Boot cannot convert to a boolean, THEN application startup SHALL fail, even when `spring.main.lazy-initialization` is `true`
(DESIGN D77). Verified by IT-3.8, IT-4.9 and unit tests; the activation and the absence of an off switch by unit tests.

**KAF-05** `[M6]` Batch listeners, whose container's listener is a `BatchMessageListener`, SHALL NOT be scoped automatically in v0.1: the
listener runs in the snapshot current on the consumer thread, and the Kafka module decodes and reports nothing for its records. The Kafka
module SHALL provide a `KafkaContext` bean (DESIGN D76) whose `forRecord(record)` returns the snapshot KAF-02 would build for that record,
under the trust setting of KAF-04, without entering it: the returned snapshot SHALL NOT be current when `forRecord` returns, and the
snapshot current before the call SHALL be current again. Because core has no public snapshot factory, `forRecord` builds the snapshot in a
temporary scope, so registered `ScopeListener`s (RT-07), such as the MDC bridge (LOG-01), MAY observe one enter and exit pair per call
(DESIGN D78). Each call SHALL report the violations KAF-02 or KAF-04 would report for the record. Verified by unit tests.

### Documentation

**KAF-06** `[M6]` The Javadoc of `KafkaContext` SHALL state:

- that batch listeners are not scoped, and that `forRecord` gives each record's snapshot (KAF-05);
- which sends are covered: those through producers that the `ProducerFactory` of a `KafkaTemplate` bean creates, including
  `execute(ProducerCallback)`, templates that are not beans but share that factory, and producers created directly from it; and the gaps: a
  `ProducerFactory` that is not a `DefaultKafkaProducerFactory`, and producers the factory created and cached before the hook was added
  (KAF-01);
- that the deadline is neither sent nor applied when a record is sent (KAF-01);
- that the headers are written into the application's `ProducerRecord`, so re-sending the same record instance is not supported: the send
  can throw `IllegalStateException` when it adds a header, and headers written before an `encode` that throws can stay on the record (KAF-01);
- which listener containers are covered: those created by listener container factory beans; that `RecordInterceptor`s run outside the
  listener scope; and that a container whose advice chain the application replaces, for example with a `ContainerCustomizer`, is not
  covered (KAF-03). It SHALL NOT promise that a `@KafkaListener` container keeps the scope when its chain is replaced;
- that the container's error handling runs in the failed record's snapshot, except for containers created or registered after startup,
  `handleOtherException` and the batch paths; and that a record whose decoding throws, with a required `MESSAGE` key in `fail` mode, cannot
  be dead-lettered and is redelivered (KAF-03);
- that only the listener invocation and the container's error handling are scoped, so code that completes a `CompletableFuture` or `Mono` a
  listener returns runs with its own thread's context (KAF-03).

Verified by the Javadoc of `KafkaContext`; the README takes it up in M7 (REL-01) (DESIGN D77, D79, D81).

### Configuration

Properties use the prefix `boundrail.context.`.

Every startup failure caused by invalid configuration also occurs when `spring.main.lazy-initialization` is `true`: a `kafka.trust-headers`
that is not a boolean (KAF-04). The Kafka adapters also use `violations.mode`, `violations.log-interval` and
`wire.max-value-length` from [003-http-trust](../003-http-trust/spec.md#configuration).

| Property | Default | Meaning |
|---|---|---|
| `kafka.trust-headers` | `true` | Trust record headers (KAF-04); with `false`, nothing is bound from them and each policy wire header present is reported; a boolean with Spring Boot's binding, and a value Boot cannot convert fails startup, even under lazy initialization |

### Key Entities

- **Record headers**: carry `MESSAGE` keys by wire name as the UTF-8 bytes of their codec strings (WIRE-01).
- **Record-mode listener**: a listener that receives one record per invocation, as opposed to a batch listener.
- **Listener scope**: the scope opened around each record-mode listener invocation, holding exactly the record's snapshot.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: IT-3.8 and IT-4.9 are enabled and green on JDK 21 and JDK 25, which makes every acceptance suite fully green.
- **SC-002**: The consumer adapter works on the ScopedValue backend (`integrationTestJdk25`) without set/restore interceptors.
- **SC-003**: No record carries an `Authorization` or `Boundrail-Deadline-Ms` header that the library wrote (IT-3.8, IT-4.9).

## Assumptions

- 005-deadlines and 006-identity are complete (IT-3.8 and IT-4.9 assert their messaging behaviour). The `MESSAGE` clause of ID-06 is
  verified here, by IT-4.9.
- Only sends through producers of a `KafkaTemplate` bean's `ProducerFactory` and listener containers created by listener container factory
  beans are covered in v0.1 (Spring Kafka 4.1, managed by the Spring Boot BOM).
- The Kafka adapters report violations through the same reporting as the Spring module's adapters (DIAG-03, DIAG-04, DIAG-11), and read the
  effective policy (POL-07). `boundrail-context-kafka` depends on `boundrail-context-spring` as `api` and reaches them through public types
  in the package `io.github.boundrail.context.spring.internal`, among them the interface `AdapterSupport`, which no contract declares and
  CORE-03 does not cover. It has its own auto-configuration, ordered after the Spring module's and Spring Boot's Kafka auto-configuration,
  and binds `kafka.trust-headers` in its own properties record, `BoundrailKafkaProperties`, so `boundrail-context-spring` stays free of
  Kafka (DESIGN D70, D78).
- The planner's T001 spike ([plan.md](plan.md), Outcome) confirmed the mechanisms behind KAF-01 and KAF-03 on both backends: the
  `ProducerPostProcessor` on each template bean's `ProducerFactory` (DESIGN D71), the `ContainerProperties` advice chain of the listener
  container factories (DESIGN D72, was DESIGN Q4), and the `CommonErrorHandler` and `AfterRollbackProcessor` decorators (DESIGN D73). The
  user accepted its outcome and the limits it found (DESIGN D79).
- Unit tests in `boundrail-context-kafka` build `ProducerRecord` and `ConsumerRecord` values directly; tests that exercise a template or a
  listener container use a Testcontainers broker, because Kafka clients are never mocked (BLD-07).
