# Feature Specification: Deadlines

**Feature Branch**: `005-deadlines`

**Created**: 2026-09-28

**Status**: Draft

**Milestone**: M4

**Input**: Roadmap milestone [M4](../../docs/roadmap.md#m4--deadlines).

API signatures: `Deadline`, `Deadlines` and `DeadlineExceededException` are defined in
[002-core-runtime](../002-core-runtime/contracts/core-api.md); this feature adds no new types. Test topology:
[001-foundation](../001-foundation/spec.md#test-topology).

## User Scenarios & Testing *(mandatory)*

In all DeadlinePropagationIT scenarios, edge is configured with `deadline.default-inbound=500ms`.

### User Story 1 - The time budget travels and shrinks (Priority: P1)

A request's remaining time budget travels to internal services as remaining milliseconds, and each service computes its own deadline on arrival.
Only trusted callers can set it, capped by a maximum; the edge can apply a default.

**Why this priority**: Without it, downstream services keep working long after the original caller gave up.

**Independent Test**: DeadlinePropagationIT IT-3.1, IT-3.5 and IT-3.6.

**Acceptance Scenarios** (DeadlinePropagationIT):

1. **IT-3.1 Budget shrinks across hops.** Given a public request authenticated as alice, when edge calls orders and orders calls billing, then the
   remaining budgets observed on arrival satisfy `500ms ≥ edge > orders > billing > 0`, and each service's deadline reflects its arrival time.
   *(DL-03, DL-05)*
2. **IT-3.5 Inbound cap.** When a trusted caller sends `Boundrail-Deadline-Ms: 600000`, then the deadline bound in orders has at most 30s remaining.
   *(DL-03)*
3. **IT-3.6 Untrusted deadline ignored.** When a public caller sends `Boundrail-Deadline-Ms: 5`, then the header is ignored with an
   `UNTRUSTED_HEADER` violation, the edge applies the 500ms default, and the request succeeds. *(DL-03, IN-02)*

---

### User Story 2 - Expired work fails fast (Priority: P1)

Outbound calls use the smaller of their configured timeout and the remaining budget. A call attempted after expiry is never sent; a request that
arrives expired is rejected without running the handler.

**Why this priority**: A declared timeout means nothing if the caller's budget is already gone.

**Independent Test**: DeadlinePropagationIT IT-3.2 – IT-3.4.

**Acceptance Scenarios** (DeadlinePropagationIT):

1. **IT-3.2 Slow external call fails fast.** Given a partner client whose configured timeout is 10s, when billing calls partner's endpoint that
   sleeps for 5s, then the call fails with `DeadlineExceededException` (stage `TIMEOUT`) no later than the remaining budget plus 200ms, partner
   receives no `Boundrail-Deadline-Ms` header, and billing responds with 504. *(DL-07, DL-08, OUT-04)*
2. **IT-3.3 Expired before send.** When an edge handler serving a request authenticated as alice sleeps past its deadline and then calls orders, then
   `DeadlineExceededException` (stage `BEFORE_SEND`) is thrown and orders receives no request. *(DL-06)*
3. **IT-3.4 Expired on arrival.** When a trusted caller sends `Boundrail-Deadline-Ms: 0` to orders, then orders responds with 504 and its handler is
   not invoked. *(DL-04)*

---

### User Story 3 - Deadlines stop at detached boundaries (Priority: P2)

Attached executors carry the deadline and refuse to start work whose deadline has passed; detached executors do not receive the deadline at all.

**Why this priority**: Fire-and-forget work must not be dropped because the request that started it ran out of time.

**Independent Test**: DeadlinePropagationIT IT-3.7.

**Acceptance Scenarios** (DeadlinePropagationIT):

1. **IT-3.7 Attached versus detached executors.** Within a request that has a deadline:
   - a task on an executor configured `attached` observes the deadline;
   - a task on a detached executor observes no deadline but does observe the tenant;
   - a task submitted to the attached executor, but started after the deadline expired (forced with a blocked single-thread pool), is not run, and
     its future fails with `DeadlineExceededException` (stage `EXECUTOR`).

   *(EXE-01, EXE-02, EXE-08, POL-04)*

---

### Edge Cases

- A request factory other than the JDK `HttpClient` one: DL-07 (warning at startup; header and fail-fast still apply).
- Network transit time is not subtracted in v0.1 (known limitation, DESIGN §7).

## Requirements *(mandatory)*

**DL-03** `[M4]` WHEN a trusted inbound request carries `Boundrail-Deadline-Ms`, the inbound filter SHALL bind
`Deadline.after(min(value, max-inbound))`. WHEN the header is absent or the caller is untrusted, and `boundrail.context.deadline.default-inbound` is
set, the filter SHALL bind `Deadline.after(default-inbound)`. Otherwise no deadline is bound.

**DL-04** `[M4]` IF the deadline bound at inbound is already expired, THEN the filter SHALL respond with `boundrail.context.deadline.expired-status`
(default 504) without invoking the rest of the filter chain.

**DL-05** `[M4]` WHEN sending an `INTERNAL_CALL` with a deadline bound, the adapter SHALL set `Boundrail-Deadline-Ms` to the remaining milliseconds,
computed immediately before sending.

**DL-06** `[M4]` IF the deadline is expired when an outbound call to any destination is about to be sent, THEN the adapter SHALL throw
`DeadlineExceededException` (stage `BEFORE_SEND`) and SHALL NOT send the request.

**DL-07** `[M4]` For outbound calls to any destination while a deadline is bound, the effective response timeout SHALL be
`min(configured client timeout, remaining)`. IF the call times out because of the deadline, THEN the adapter SHALL throw `DeadlineExceededException`
(stage `TIMEOUT`). v0.1 SHALL support the JDK `HttpClient`-based request factory. For any other request factory, the library SHALL log a warning at
startup that timeout capping is unavailable, while still applying DL-05 and DL-06.

**DL-08** `[M4]` WHERE `boundrail.context.deadline.map-exception=true` (default), a `DeadlineExceededException` escaping a request handler SHALL
produce the `expired-status` response.

**DL-09** `[M4]` The library SHALL count deadline expiries in the Micrometer counter `boundrail.deadline.exceeded`, tagged with `stage`, when
Micrometer is present.

**EXE-08** `[M4]` IF a task submitted to an `ATTACHED` executor starts after its captured deadline has expired, THEN the library SHALL NOT run the
task. It SHALL complete the task's future exceptionally with `DeadlineExceededException` (stage `EXECUTOR`); for plain `Executor.execute`, it SHALL
report the exception through the thread's uncaught exception handler.

### Configuration

Properties use the prefix `boundrail.context.`.

| Property | Default | Meaning |
|---|---|---|
| `deadline.default-inbound` | unset | Budget applied when no trusted deadline is present (DL-03) |
| `deadline.max-inbound` | `30s` | Cap on trusted inbound deadlines (DL-03) |
| `deadline.expired-status` | `504` | Status for expired requests (DL-04, DL-08) |
| `deadline.map-exception` | `true` | Map `DeadlineExceededException` to the status (DL-08) |

### Key Entities

- **Deadline**: an instant on a monotonic clock; travels as remaining milliseconds in `Boundrail-Deadline-Ms`.
- **Expiry stage**: where a deadline expiry was detected (`INBOUND`, `BEFORE_SEND`, `TIMEOUT`, `EXECUTOR`, `APPLICATION`).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: IT-3.1 – IT-3.7 are enabled and green on JDK 21 and JDK 25.
- **SC-002**: A call to a slow dependency fails within the remaining budget plus 200ms, regardless of a longer configured client timeout (IT-3.2).
- **SC-003**: No expired request reaches a handler, and no request is sent after expiry.

## Assumptions

- 004-async-diagnostics is complete (executor modes, IT-3.7).
- Only the JDK `HttpClient` request factory supports timeout capping in v0.1 (DESIGN Q1).
- Deadline behaviour for Kafka messages belongs to [007-kafka](../007-kafka/spec.md) (IT-3.8).
