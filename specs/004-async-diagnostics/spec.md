# Feature Specification: Async Work and Diagnostics

**Feature Branch**: `004-async-diagnostics`

**Created**: 2026-09-28

**Status**: Draft

**Milestone**: M3

**Input**: Roadmap milestone [M3](../../docs/roadmap.md#m3--async-work-and-diagnostics).

API signatures: [contracts/runtime-listeners.md](contracts/runtime-listeners.md), [contracts/executors-and-test.md](contracts/executors-and-test.md).
Test topology: [001-foundation](../001-foundation/spec.md#test-topology).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Context survives Spring executors and `@Async` (Priority: P1)

Work handed to Spring task executors, `@Async` methods and explicitly wrapped executors runs with the submitter's context, filtered by the executor's
declared mode.

**Why this priority**: Context silently lost at an undecorated executor is the most common propagation bug.

**Independent Test**: ContextPropagationIT IT-1.1 and IT-1.3.

**Acceptance Scenarios** (ContextPropagationIT):

1. **IT-1.1 Decorated executor carries context.** Given a public request authenticated as alice (tenant `acme`), when edge runs work on
   `taskExecutor` (an auto-decorated `ThreadPoolTaskExecutor` in detached mode, with Micrometer's context-propagating decorator also configured) and
   that work calls orders, then orders observes `tenant=acme`. *(EXE-02, EXE-03, OUT-04)*
2. **IT-1.3 Explicit wrapping fixes it.** Given `rawExecutor` wrapped with `BoundExecutors.detached(...)`, when the same work runs, then orders
   observes `tenant=acme` and the audit reports the executor as `COVERED`. *(EXE-04, DIAG-06)*

---

### User Story 2 - Lost context fails loudly, with evidence (Priority: P1)

When context is lost, the next remote crossing fails (or reports) with a violation that states only what was observed: thread, capture site, and the
executor the thread belongs to. A startup audit lists executors that are not covered.

**Why this priority**: Turns lost context into a test failure instead of a production mystery.

**Independent Test**: ContextPropagationIT IT-1.2, IT-1.4, IT-1.5 and IT-1.8.

**Acceptance Scenarios** (ContextPropagationIT):

1. **IT-1.2 Undecorated executor fails loudly.** Given violation mode `fail`, when edge runs the same work on `rawExecutor` (a plain `ExecutorService`
   bean whose threads are named `rawExecutor-N`), then the work's future completes exceptionally with `ContextViolationException`, whose violation
   has kind `MISSING_REQUIRED`, key `tenant`, boundary `INTERNAL_CALL`, and an `executorEvidence` that names `rawExecutor` with status `GAP`. Orders
   receives no request. *(EXE-04, POL-08, DIAG-02, DIAG-04)*
2. **IT-1.4 Report mode continues.** Given violation mode `report`, when the IT-1.2 work runs twice in a row, then orders receives both requests
   without a `Tenant-Id` header, `boundrail.context.violations{kind=missing_required,key=tenant,boundary=internal_call}` increases by two, and
   exactly one WARN entry is logged. *(DIAG-02, DIAG-03)*
3. **IT-1.5 Startup audit.** The startup audit reports `taskExecutor` as `COVERED (detached)` and `rawExecutor` as `GAP`. With
   `audit.fail-on-gap=true`, the edge context fails to start with an error that lists `rawExecutor`. *(DIAG-06)*
4. **IT-1.8 Capture sites are recorded in tests, and only where observed.** Under `@BoundrailTest`, a `MISSING_REQUIRED` violation raised after
   `Context.without(tenant)` in an edge controller carries a capture site in that controller class. The IT-1.2 violation, whose task ran with
   `EMPTY`, carries no capture site. *(DIAG-04, DIAG-05, TEST-02)*

---

### User Story 3 - Logs carry context (Priority: P2)

Loggable keys appear in the MDC for exactly the duration of every scope, so `%X{tenant}` works in log patterns without extra code.

**Why this priority**: High value for operations, but independent of propagation correctness.

**Independent Test**: RC-9 on all three JDK tasks and IT-1.6.

**Acceptance Scenarios**:

1. **RC-9 Scope listeners** (RuntimeConformanceIT). Two registered listeners are entered in registration order and restored in reverse order for
   every nested scope, including when the operation throws. A listener that throws does not change the operation's result. *(RT-07)*
2. **IT-1.6 MDC.** A log line written inside the IT-1.1 async work contains `tenant=acme` through `%X{tenant}`, and a later task on the same pooled
   thread without context logs no tenant. *(LOG-01, RT-03)*

---

### User Story 4 - Scheduled jobs get a system context (Priority: P3)

A `@Scheduled` method always starts in a fresh context whose initiator names the job, never in context left over from another task.

**Why this priority**: Small, self-contained, and prevents accidental context reuse on scheduler threads.

**Independent Test**: IT-1.7.

**Acceptance Scenarios** (ContextPropagationIT):

1. **IT-1.7 Scheduled jobs get a system context.** When a `@Scheduled` method on edge (bean `cleanupJob`, method `run`) runs, then the snapshot it
   records to `ObservedContext` contains exactly `initiator=system:scheduled:cleanupJob.run`. *(EXE-06)*

---

### Edge Cases

- An executor already has a task decorator (e.g. Micrometer's): EXE-03 composes, with the library's decorator outermost.
- `CompletableFuture.*Async` without an executor: not covered, documented (EXE-07).
- Executors created in code are invisible to the audit: runtime validation covers them (DIAG-07).

## Requirements *(mandatory)*

### Runtime hooks

**RT-07** `[M3]` WHEN any scope is entered (`ContextBinding.run`/`call`, `ContextSnapshot.run`/`call`, or a wrapped operation), the runtime SHALL call
`onEnter` on every registered listener in registration order, and SHALL call the returned restorers in reverse order when the scope exits, whether
normally or exceptionally. IF a listener or restorer throws, THEN the runtime SHALL log the exception through `System.Logger` and continue; the
operation's outcome SHALL NOT change.

**RT-08** `[M3]` The capture-site mode (DIAG-05) SHALL be held by the runtime. It SHALL be initialised from the system properties
`boundrail.context.diagnostics.capture-sites` and `boundrail.context.diagnostics.sample-rate`, and MAY be changed with `ContextRuntime.captureSites`.

### Executors and async work

**EXE-01** `[M3]` Every executor managed by the library SHALL have a mode, `ATTACHED` or `DETACHED`. The default is `DETACHED`, overridable per bean
with `boundrail.context.executors.modes.<bean-name>`.

**EXE-02** `[M3]` WHEN a task is submitted to a library-managed executor, the library SHALL capture the current snapshot at submission, filter it with
the policy for the executor's mode (POL-04), and run the task inside the filtered snapshot.

**EXE-03** `[M3]` WHERE `boundrail.context.executors.auto-decorate=true` (default), the Spring module SHALL install the library's `TaskDecorator` on
every `ThreadPoolTaskExecutor`, `SimpleAsyncTaskExecutor` and `ThreadPoolTaskScheduler` bean before initialisation. If a decorator is already
configured, it SHALL compose with it, with the library's decorator outermost so that the existing decorator runs inside the scope.

**EXE-04** `[M3]` Beans that are plain `Executor` or `ExecutorService` instances, and not one of the Spring types in EXE-03, SHALL NOT be wrapped
automatically. Instances returned by `BoundExecutors` SHALL be recognised as covered by the audit (DIAG-06).

**EXE-05** `[M3]` `@Async` methods SHALL run with context according to the mode of the executor they use.

**EXE-06** `[M3]` WHEN a `@Scheduled` method is invoked, the library SHALL run it in a fresh snapshot containing only
`INITIATOR = "system:scheduled:<beanName>.<methodName>"`, never a context left over from another task.

**EXE-07** `[M3]` The documentation SHALL state that `CompletableFuture.*Async` methods without an explicit executor use the common pool and are not
covered.

### Diagnostics

**DIAG-04** `[M3]` A violation SHALL contain only observed evidence:

- kind, key, boundary, and destination (for remote boundaries);
- the current thread's name;
- the capture site of the current snapshot, if one was recorded (DIAG-05);
- `executorEvidence`, when the thread name starts with the configured thread-name prefix or the bean name of an executor bean known to the audit,
  naming that bean and its audit status.

It SHALL NOT assert causes that were not observed.

**DIAG-05** `[M3]` `boundrail.context.diagnostics.capture-sites` SHALL accept:

- `off` (default): nothing is recorded;
- `on`: the first caller frame outside the library is recorded with `StackWalker` whenever a snapshot is created by a binding or an inbound adapter;
- `sampled`: recording happens for a fraction of snapshots given by `boundrail.context.diagnostics.sample-rate` (default 0.01).

The Spring module SHALL apply these properties through `ContextRuntime.captureSites` (RT-08). The mode is JVM-wide (RT-09).

**DIAG-06** `[M3]` WHERE `boundrail.context.audit.enabled=true` (default), the Spring module SHALL, on `ApplicationReadyEvent`, report every bean
assignable to `java.util.concurrent.Executor` as either `COVERED` (with its mode) or `GAP`, and log a summary. WHERE
`boundrail.context.audit.fail-on-gap=true`, the application SHALL fail to start and list the gaps.

**DIAG-07** `[M3]` The documentation SHALL state that the audit only sees Spring beans, and that runtime validation (POL-08) covers executors created
in code.

### Logging

**LOG-01** `[M3]` WHERE SLF4J is present, the Spring module SHALL register a `ScopeListener` (RT-07) that, WHEN a scope is entered, puts every bound
loggable key into the MDC under the key's name, using its codec encoding (or `toString()` when it has no codec). Its restorer SHALL restore the
previous MDC values for those names when the scope exits.

**LOG-02** `[M3]` `AUTHORITY` and `DEADLINE` SHALL never be written to the MDC.

### Test support

**TEST-02** `[M3]` The test module SHALL provide a JUnit Jupiter extension, `@BoundrailTest`, that sets the violation mode to `fail` and capture sites
to `on` for the annotated test class, and exposes the recorded violations.

**TEST-03** `[M3]` The test module SHALL provide AssertJ-style assertions:

- `ContextAssertions.assertThat(snapshot)` with `contains(key, value)`, `doesNotContain(key)` and `isEmpty()`;
- `ContextAssertions.assertThatViolations()` with `hasSize`, `containsKind` and `isEmpty`.

### Configuration

Properties use the prefix `boundrail.context.`.

| Property | Default | Meaning |
|---|---|---|
| `executors.auto-decorate` | `true` | Decorate Spring task executors (EXE-03) |
| `executors.modes.<bean-name>` | `detached` | `attached` or `detached` (EXE-01) |
| `diagnostics.capture-sites` | `off` | `off`, `on` or `sampled` (DIAG-05); JVM-wide (RT-08) |
| `diagnostics.sample-rate` | `0.01` | Fraction recorded when sampled; JVM-wide (RT-08) |
| `audit.enabled` | `true` | Startup executor audit (DIAG-06) |
| `audit.fail-on-gap` | `false` | Fail startup when there are gaps (DIAG-06) |

### Key Entities

- **Executor mode**: `ATTACHED` or `DETACHED`, declared per executor bean.
- **Audit entry**: an executor bean with status `COVERED (mode)` or `GAP`.
- **Capture site**: the first caller frame outside the library where a snapshot was created.
- **Scope listener**: a hook called on every scope entry, whose restorer runs on exit.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: RC-9 is green on all three JDK tasks; IT-1.1 – IT-1.8 are enabled and green on JDK 21 and JDK 25.
- **SC-002**: IT-1.1 passes with Micrometer's context-propagating decorator present.
- **SC-003**: With capture sites `off`, no stack walking happens on any code path.

## Assumptions

- 003-http-trust is complete (violations, outbound interceptor, required-key checks).
- `CompletableFuture` default-pool coverage and Reactor are out of scope for v0.1.
