# Feature Specification: Kafka

**Feature Branch**: `007-kafka`

**Created**: 2026-09-28

**Status**: Draft

**Milestone**: M6

**Input**: Roadmap milestone [M6](../../docs/roadmap.md#m6--kafka).

API signatures: [contracts/kafka-api.md](contracts/kafka-api.md). Test topology: [001-foundation](../001-foundation/spec.md#test-topology).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Context travels with messages under detached semantics (Priority: P1)

Records produced during a request carry the keys declared for `MESSAGE`. Each record-mode listener invocation runs in exactly the context its record
carried, which never includes the deadline or the authority, so an event processed long after the request still succeeds and knows who caused it.

**Why this priority**: The messaging half of the deadline and identity stories; without it, all suites cannot be green.

**Independent Test**: IT-3.8 (DeadlinePropagationIT) and IT-4.9 (AuthorityPropagationIT) against the Testcontainers broker.

**Acceptance Scenarios**:

1. **IT-3.8 Kafka event outlives the request.** When edge publishes an event to `orders.events` during a request with a 500ms deadline, and
   billing's listener starts processing it at least 4 seconds later, then processing succeeds, and billing observes no deadline, `tenant=acme` and
   `initiator=user:alice`. *(KAF-01, KAF-02, KAF-04)*
2. **IT-4.9 Messages carry the initiator, never the authority.** When edge publishes an event during alice's request, then billing's listener
   observes `initiator=user:alice`, no authority, and no `Authorization` record header exists. *(ID-06, KAF-04)*

---

### User Story 2 - Manual scoping for batch listeners (Priority: P3)

Batch listeners are not scoped automatically in v0.1; a helper returns the snapshot a record would have been bound with.

**Why this priority**: A small escape hatch until automatic batch support is designed.

**Independent Test**: Unit tests for KAF-05.

**Acceptance Scenarios**: Covered by unit tests whose display names start with `KAF-05`. No acceptance-suite scenario.

---

### Edge Cases

- Consecutive records on the same consumer thread: nothing leaks between them (KAF-02).
- `kafka.trust-headers=false`: nothing is bound, and each policy header present is reported (KAF-04).
- A required key is unbound when producing: the required-key check runs before the record reaches the producer (KAF-01).

## Requirements *(mandatory)*

**KAF-01** `[M6]` The Kafka module SHALL write, at send time and on the sending thread, the wire headers of bound keys that propagate to `MESSAGE`
onto every record produced through Spring Kafka's `KafkaTemplate`. The required-key check (POL-08) SHALL be performed before the record is handed to
the producer.

**KAF-02** `[M6]` WHEN a record is delivered to a record-mode `@KafkaListener`, the Kafka module SHALL decode the headers of keys that propagate to
`MESSAGE`, and invoke the listener inside a scope whose snapshot contains exactly those bindings. Nothing SHALL leak between consecutive records on
the same consumer thread.

**KAF-03** `[M6]` The consumer adapter SHALL wrap listener invocation around-style. Set/restore-style record interceptors alone SHALL NOT be the
scoping mechanism, because they cannot drive the ScopedValue backend.

**KAF-04** `[M6]` WHERE `boundrail.context.kafka.trust-headers=true` (default), message headers SHALL be treated as trusted for decoding. WHERE it is
`false`, the consumer adapter SHALL bind none of them, and SHALL report one `UNTRUSTED_HEADER` violation per policy wire header present. `AUTHORITY`
and `DEADLINE` SHALL never be read from messages, regardless of configuration.

**KAF-05** `[M6]` Batch listeners SHALL NOT be scoped automatically in v0.1. `KafkaContext.forRecord(record)` SHALL return the snapshot KAF-02 would
bind, for manual use.

### Configuration

Properties use the prefix `boundrail.context.`.

| Property | Default | Meaning |
|---|---|---|
| `kafka.trust-headers` | `true` | Trust message headers (KAF-04) |

### Key Entities

- **Record headers**: carry `MESSAGE` keys by wire name as UTF-8 codec strings (WIRE-01).
- **Listener scope**: the per-record scope opened around each listener invocation.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: IT-3.8 and IT-4.9 are enabled and green on JDK 21 and JDK 25, which makes every acceptance suite fully green.
- **SC-002**: The consumer adapter works on the ScopedValue backend (JDK 25 run) without set/restore interceptors.

## Assumptions

- 005-deadlines and 006-identity are complete (IT-3.8 and IT-4.9 assert their messaging behaviour).
- Only Spring Kafka's `KafkaTemplate` and record-mode `@KafkaListener` are covered in v0.1.
- How listeners are wrapped (container advice chain or AOP around listener methods) is decided in a spike (DESIGN Q4).
