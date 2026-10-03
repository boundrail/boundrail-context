# Feature Specification: Async Work and Diagnostics

**Feature Branch**: `004-async-diagnostics`

**Created**: 2026-09-28

**Status**: Ready for planning

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

1. **IT-1.1 Decorated executor carries context.** Given a public request authenticated as alice (tenant `acme`), when edge runs work through an
   `@Async` method on `taskExecutor` (an auto-decorated `ThreadPoolTaskExecutor` in detached mode, with Micrometer's context-propagating decorator
   also configured) and that work calls orders, then orders observes `tenant=acme`. *(EXE-02, EXE-03, EXE-05, OUT-04)*
2. **IT-1.3 Explicit wrapping fixes it.** Given `boundExecutor`, an edge bean declared as `BoundExecutors.detached(...)` around a plain
   `ExecutorService`, when the same work runs on it, then orders observes `tenant=acme` and the audit reports `boundExecutor` as
   `COVERED (detached)`. *(EXE-04, DIAG-06)*

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
   `EMPTY`, carries no capture site. *(DIAG-04, DIAG-05, DIAG-10, TEST-02)*

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

### User Story 5 - Spring MVC async requests keep the request's context (Priority: P2)

A handler that returns a `Callable` or a `DeferredResult` sees the same context in the async part of the request as in its original dispatch.

**Why this priority**: Closes the known M2 limitation (DESIGN Q8, D39); without it, async MVC handlers lose context silently.

**Independent Test**: IT-1.9.

**Acceptance Scenarios** (ContextPropagationIT):

1. **IT-1.9 Spring MVC async processing keeps the request's context.** Given a public request authenticated as alice (tenant `acme`), when an
   edge handler returns a `Callable` that calls orders, then orders observes `tenant=acme`. When an edge handler returns a `DeferredResult` that a
   thread with no context completes, then an edge `HandlerInterceptor` that records the bound `tenant` in `postHandle` of the async dispatch
   observes `tenant=acme`. *(IN-09, EXE-09)*

---

### Edge Cases

- An executor already has a task decorator (e.g. Micrometer's): EXE-03 composes, with the library's decorator outermost.
- `CompletableFuture.*Async` without an executor: not covered, documented (EXE-07).
- Executors created in code are invisible to the audit: runtime validation covers them (DIAG-07).
- An unrecognised `boundrail.context.diagnostics.capture-sites` or `sample-rate` system property: RT-08 (a warning is logged and the default is
  used, as RT-04 does for the backend override).
- Invalid configuration with `spring.main.lazy-initialization=true`: an invalid `executors.modes.<bean-name>`, `diagnostics.capture-sites` or
  `diagnostics.sample-rate` still fails startup (EXE-01, DIAG-05).
- A `null` argument passed to the M3 contracts: CORE-14.
- A Spring upgrade renames a private field the library reads: EXE-10 (startup fails, naming the class and the field).
- A `@Scheduled` method that returns a `Publisher` or is a Kotlin `suspend` function, or an application that registers its own
  `internalScheduledAnnotationProcessor`: EXE-06 (no system context; in the second case, one startup WARN).
- A nested `Context.without(tenant)` scope: LOG-01 (`tenant` is removed from the MDC for that scope, and restored when it exits).
- Code that completes a `DeferredResult` on an application thread: IN-09 (the async dispatch still gets the request's snapshot); the completing
  code itself runs with whatever context its thread has (EXE-04).
- Several application contexts in one JVM: each registers its own MDC listener (LOG-01) and applies only the capture-site properties it sets
  (DIAG-05); executor evidence uses only the executors of the context that reports the violation (DIAG-04).
- A thread name that matches the prefixes of two executor beans, such as `task-` and `task-io-`: DIAG-04 (the longest prefix wins).

## Requirements *(mandatory)*

### API conventions

**CORE-14** `[M3]` CORE-03 and CORE-04 SHALL apply to the types and methods declared in
[contracts/runtime-listeners.md](contracts/runtime-listeners.md) and [contracts/executors-and-test.md](contracts/executors-and-test.md) as they
do to `contracts/core-api.md`, with the same exceptions: a `null` argument SHALL throw `NullPointerException` at that call. Verified by unit
tests.

### Runtime hooks

**RT-07** `[M3]` WHEN any scope is entered (`ContextBinding.run`/`call`, `ContextSnapshot.run`/`call`, or a wrapped operation), the runtime SHALL call
`onEnter` on every registered listener in registration order, and SHALL call the returned restorers in reverse order when the scope exits, whether
normally or exceptionally. IF a listener or restorer throws a `RuntimeException`, THEN the runtime SHALL log a WARNING through
`System.Logger` that names that listener (for a restorer, the listener that returned it) and carries the exception, and SHALL continue; the
operation's outcome SHALL NOT change. An `Error` SHALL NOT be caught. IF `onEnter` throws an `Error`, THEN the runtime SHALL NOT run the
operation or call `onEnter` on later listeners, SHALL call the restorers already returned by earlier listeners in reverse order, and SHALL then
let the `Error` propagate. IF a restorer throws an `Error`, THEN the `Error` SHALL propagate at once, and the remaining restorers SHALL NOT be
called. IF `onEnter` returns `null`, THEN the runtime SHALL NOT run the operation, SHALL call the restorers already returned by earlier
listeners in reverse order, and SHALL then throw `NullPointerException` (CORE-14). Adding a listener that is already registered SHALL leave it
registered once, in its original position, and removing a listener that is not registered SHALL do nothing.

**RT-08** `[M3]` The capture-site mode (DIAG-05) SHALL be held by the runtime. It SHALL be initialised from the system properties
`boundrail.context.diagnostics.capture-sites` and `boundrail.context.diagnostics.sample-rate`, and MAY be changed with `ContextRuntime.captureSites`.
The system properties take the values DIAG-05 lists (`off`, `on`, `sampled`; a sample rate from 0.0 to 1.0). The sample-rate property SHALL be parsed
with `Double.parseDouble`, so any syntax it accepts, such as `5e-1`, is valid when the result is in 0.0 – 1.0; NaN is rejected. IF a system property
is set to any other value, THEN the runtime SHALL log a warning through `System.Logger` and use that property's default, as RT-04 does. IF
`ContextRuntime.captureSites` is called with a sample rate that is NaN or outside 0.0 – 1.0, THEN it SHALL throw `IllegalArgumentException` and leave
the mode unchanged. `ContextRuntime.captureSites()` and `ContextRuntime.sampleRate()` SHALL return the current mode and sample rate. Verified by unit
tests.

**RT-09** `[M3]` The only JVM-wide library state SHALL be the current-snapshot slot, the registered scope listeners and the capture-site mode. Every
other piece of state, including the effective policy, violation reporting and its rate limiter, and metrics, SHALL belong to a single Spring
application context. The one exception is test support: `boundrail-context-test` MAY hold the JVM-wide violation recorder of TEST-02, because it
is never on an application's production classpath (DESIGN D40).

### Executors and async work

**EXE-01** `[M3]` Every executor managed by the library SHALL have a mode, `ATTACHED` or `DETACHED`. For a bean the Spring module decorates (EXE-03),
the default is `DETACHED`, overridable with `boundrail.context.executors.modes.<bean-name>`; the bean name SHALL be matched ignoring case, because
relaxed binding lowercases map keys taken from environment variables. `executors.modes` SHALL apply to decorated beans only: an executor returned by
`BoundExecutors` takes its mode from the method that created it (EXE-04), and a plain executor bean has none. The mode SHALL be matched with Spring
Boot's relaxed enum binding, as DIAG-02 matches the violation mode, and an empty or blank value SHALL mean `detached`. IF a mode matches neither
value, THEN application startup SHALL fail, even when `spring.main.lazy-initialization` is `true`. IF two `executors.modes` keys match the
same bean name ignoring case and give different modes, THEN application startup SHALL fail, even when `spring.main.lazy-initialization` is
`true`; the same mode under both is accepted. Verified by unit tests.

**EXE-02** `[M3]` WHEN a task is submitted to a library-managed executor, the library SHALL capture the current snapshot at submission, filter it with
the policy for the executor's mode (POL-04), and run the task inside the filtered snapshot. Executors the Spring module decorates (EXE-03) SHALL
filter with the effective policy (POL-07); `BoundExecutors` SHALL filter as EXE-04 defines.

**EXE-03** `[M3]` WHERE `boundrail.context.executors.auto-decorate=true` (default), the Spring module SHALL install the library's `TaskDecorator` on
every `ThreadPoolTaskExecutor`, `SimpleAsyncTaskExecutor` and `ThreadPoolTaskScheduler` bean before initialisation. If a decorator is already
configured, it SHALL compose with it, with the library's decorator outermost so that the existing decorator runs inside the scope.

**EXE-04** `[M3]` Beans that are plain `Executor` or `ExecutorService` instances, and not one of the Spring types in EXE-03, SHALL NOT be wrapped
automatically. Instances returned by `BoundExecutors` SHALL be recognised as covered by the audit (DIAG-06). The `BoundExecutors` methods that take
a `ContextPolicy` SHALL filter with that policy; those that do not SHALL filter with `BuiltInKeys.defaultPolicy()`, so the rules an application
policy declares for its own keys at `ATTACHED` or `DETACHED` are not applied, and their Javadoc SHALL say so (DESIGN D43).

**EXE-05** `[M3]` `@Async` methods SHALL run with context according to the mode of the executor they use.

**EXE-06** `[M3]` WHEN the scheduler invokes a `@Scheduled` method, the library SHALL run it in a fresh snapshot containing only
`INITIATOR = "system:scheduled:<beanName>.<methodName>"`, never a context left over from another task. WHEN application code calls the same
method directly, it SHALL run in the caller's context, unchanged. This SHALL cover ordinary `@Scheduled` methods only: methods that return a
reactive `Publisher` and Kotlin `suspend` methods are not covered (EXE-07). The library SHALL provide this by replacing only the bean
definition of Spring's own scheduled-annotation post-processor, registered by Spring as `internalScheduledAnnotationProcessor`. IF the
application registered its own definition under that name, THEN the library SHALL leave it, and SHALL log one WARN entry at startup stating
that `@Scheduled` methods are not given a system context (DESIGN D47).

**EXE-07** `[M3]` The Javadoc of `BoundExecutors` SHALL state that `CompletableFuture.*Async` methods without an explicit executor use the common
pool and are not covered, and that `@Scheduled` methods returning a reactive `Publisher` and Kotlin `suspend` `@Scheduled` methods are not
given a system context (EXE-06; DESIGN D45, D47).

**EXE-09** `[M3]` WHERE Spring MVC is present, a `Callable` returned by a handler, or a `WebAsyncTask` with no executor of its own, SHALL run
inside the request's snapshot, captured when Spring MVC submits it and filtered for `ATTACHED` (POL-04), because the request awaits its result.
This SHALL hold whatever the mode of the executor Spring MVC submits it to (DESIGN D39). Verified by IT-1.9, and by unit tests for the
`ATTACHED` filtering.

**EXE-10** `[M3]` Spring offers no getter for the values EXE-03 and EXE-09 must compose with, so the Spring module MAY read them by reflection,
once per bean: the private `taskDecorator` field of `ThreadPoolTaskExecutor`, `SimpleAsyncTaskExecutor` and `ThreadPoolTaskScheduler`
(EXE-03), and the private `taskExecutor` field of `RequestMappingHandlerAdapter` (EXE-09). It SHALL read no other private member, and SHALL set
values only through Spring's public setters. IF such a field is missing at runtime, for example because a Spring upgrade renamed it, THEN
application startup SHALL fail, with a message naming the class and the field, even when `spring.main.lazy-initialization` is `true` for the
beans the library processes at startup (DESIGN D47). Verified by unit tests.

### Spring MVC async processing

**IN-09** `[M3]` The Spring module SHALL register `ContextInboundFilter` for the `REQUEST` and `ASYNC` dispatch types. On a request's initial
dispatch, the library SHALL store, as an attribute of the request, the snapshot current inside the innermost scope its filters enter for that
request: the scope of `ContextAuthenticationFilter` (IN-04) where it enters one, otherwise that of `ContextInboundFilter` (IN-01), or the
caller's existing scope when neither enters one. WHEN the request is dispatched again with the `ASYNC` dispatch type, `ContextInboundFilter`
SHALL run the rest of the chain inside the stored snapshot (CORE-10), and SHALL NOT ask the `InboundTrustVerifier` again, decode headers or
report violations. `ContextAuthenticationFilter` SHALL NOT run on `ASYNC` dispatches, so contributors are invoked once per request. IF an
`ASYNC` dispatch finds no stored snapshot, THEN `ContextInboundFilter` SHALL run the rest of the chain in the current scope (DESIGN D39).
Verified by IT-1.9 and unit tests.

### Diagnostics

**DIAG-04** `[M3]` A violation SHALL contain only observed evidence:

- kind, key, boundary, and destination (for outbound remote crossings; empty for inbound values, DIAG-08);
- the current thread's name;
- `captureSite`: the capture site of the current snapshot, if one was recorded (DIAG-05, DIAG-10);
- `executorEvidence`: when the current thread's name starts with the thread-name prefix of an executor bean of an EXE-03 type, or with the bean name
  of any executor bean, in the application context that reports the violation, the string `<bean> (<status>)`, where the status is `GAP` or `COVERED`
  followed by the mode in lowercase, for example `rawExecutor (GAP)` or `taskExecutor (COVERED detached)`. Where several prefixes match, the longest
  SHALL win. The Spring module SHALL build its table of executor beans (DIAG-12) once the application context has started, whether or not the audit is
  enabled (DIAG-06); a violation reported before then has no executor evidence.

Every adapter in the Spring module SHALL fill these components when it reports a violation. The WARN log entry (DIAG-03) SHALL include the
capture site and the executor evidence, when present, after the thread name (DESIGN D42). A violation SHALL NOT assert causes that were not
observed.

**DIAG-05** `[M3]` `boundrail.context.diagnostics.capture-sites` SHALL accept:

- `off` (default): nothing is recorded;
- `on`: the capture site (DIAG-10) is recorded with `StackWalker` whenever a binding (CORE-07) creates a snapshot, including the bindings of
  adapters and of the `@Scheduled` support (EXE-06);
- `sampled`: recording happens for a fraction of snapshots given by `boundrail.context.diagnostics.sample-rate` (default 0.01).

The Spring module SHALL apply each of these properties that is set in the application context's `Environment` through
`ContextRuntime.captureSites` (RT-08), keeping the current value of the other. An unset property SHALL leave the JVM-wide value unchanged, so a
context that sets neither does not reset a mode set by system property, by another context or by `@BoundrailTest` (TEST-02; DESIGN D41). A
blank `sample-rate` SHALL count as not set. The
mode is JVM-wide (RT-09).
`capture-sites` SHALL be matched with Spring Boot's relaxed enum binding, as DIAG-02 matches the violation mode, and an empty or blank value SHALL
mean `off`. IF `capture-sites` matches none of its values, or `sample-rate` is NaN or outside 0.0 – 1.0, THEN application startup SHALL fail,
even when `spring.main.lazy-initialization` is `true`.

**DIAG-06** `[M3]` WHERE `boundrail.context.audit.enabled=true` (default), the Spring module SHALL, on `ApplicationReadyEvent`, classify every bean
assignable to `java.util.concurrent.Executor` that DIAG-12 covers as `COVERED` with its mode, or `GAP`. A bean is covered when it is of an EXE-03 type
and carries the library's decorator, or was returned by `BoundExecutors` (EXE-04). It SHALL log one entry per bean, in ascending order of bean name:
at INFO `Executor audit: <bean> COVERED (<mode>)`, with the mode in lowercase, or at WARN `Executor audit: <bean> GAP`; then one INFO entry
`Executor audit: <n> covered, <m> gaps` (DESIGN D42). WHERE `boundrail.context.audit.fail-on-gap=true` and there is at least one gap, the application
SHALL fail to start with an exception whose message lists the bean name of every gap.

**DIAG-07** `[M3]` The Javadoc of `BoundExecutors` SHALL state that the startup audit only sees Spring beans, and which ones (DIAG-12), and that
runtime validation (POL-08) covers executors created in code (DESIGN D45).

**DIAG-12** `[M3]` The executor table (DIAG-04) and the audit (DIAG-06) SHALL cover only singleton executor beans defined in the application
context itself and instantiated at startup. Lazy beans not yet instantiated, prototype beans and beans of ancestor contexts SHALL be skipped,
and SHALL NOT be instantiated by the table or the audit. Verified by unit tests.

**DIAG-10** `[M3]` `ContextSnapshot.captureSite()` SHALL return the capture site recorded when the snapshot was created, or empty if none was
recorded. A capture site SHALL be the `StackTraceElement.toString()` form of the first stack frame whose declaring class is in neither the
package `io.github.boundrail.context` nor `io.github.boundrail.context.internal` (`boundrail-context-core`). For a binding made by application
code it is therefore the application's frame; for a snapshot an adapter in another library module creates, such as `ContextInboundFilter`, it is
the adapter's frame (DESIGN D41). `ContextPolicy.filter` (POL-04) SHALL keep the capture site of the snapshot it filters, and running or wrapping
a snapshot (CORE-10) SHALL NOT change it. `ContextSnapshot.EMPTY` has none. Verified by IT-1.8 and unit tests.

**DIAG-11** `[M3]` WHEN a violation is reported (DIAG-03), the Spring module SHALL also pass it to every `ViolationListener` bean in the application
context, in bean order, after counting it, whether or not its log entry is rate-limited. IF a listener throws, THEN the exception SHALL be logged at
WARN, with the exception, the remaining listeners SHALL still be called, and the operation's outcome SHALL NOT change. Verified by unit tests.

### Logging

**LOG-01** `[M3]` WHERE SLF4J is present, the Spring module SHALL register one `ScopeListener` (RT-07) per application context, using that
context's effective policy, and SHALL remove it when the context closes. WHEN a scope is entered, the listener SHALL, for every key that policy
marks loggable (except as LOG-02 states), put the key's value into the MDC under the key's name, using its codec encoding (or `toString()` when
it has no codec), if the key is bound in the entered snapshot, and remove that name from the MDC if it is not. Its restorer SHALL, when the
scope exits, restore the previous MDC value of each of those names, or remove the name where it had none. With several application contexts in
one JVM, each listener handles only the keys its own policy marks loggable (DESIGN D44).

**LOG-02** `[M3]` `AUTHORITY` and `DEADLINE` SHALL never be written to the MDC. Verified by unit tests.

### Test support

**TEST-02** `[M3]` The test module SHALL provide `@BoundrailTest`, an annotation that registers a JUnit Jupiter extension. For the annotated test
class, the extension SHALL set the capture-site mode to `on` (RT-08) before the class's first test, and SHALL restore the mode and sample rate
in effect before it after the class's last test. It SHALL record, from the start to the end of each test method, every violation reported
(DIAG-03) in any application context in the JVM, through a `ViolationListener` (DIAG-11) that the test module auto-configures, and SHALL
expose them to that test as a `RecordedViolations` parameter and through `ContextAssertions.assertThatViolations()` (TEST-03). While no
`@BoundrailTest` test is running, the listener SHALL keep nothing. IF `ContextAssertions.assertThatViolations()` or `RecordedViolations.all()`
is called while no `@BoundrailTest` test method is running, THEN it SHALL throw `IllegalStateException`. Because the recorder is JVM-wide, running
`@BoundrailTest` test methods in parallel is not supported, and the Javadoc of `@BoundrailTest` SHALL say so. The extension SHALL NOT change
the violation mode, which stays each application's `violations.mode` (default `fail`, DESIGN D24, D40). That listener is the test module's only
auto-configuration; `TestInboundTrustVerifier` and `peer()` stay not auto-configured (TEST-01). Verified by IT-1.8 and unit tests.

**TEST-03** `[M3]` The test module SHALL provide AssertJ-style assertions:

- `ContextAssertions.assertThat(snapshot)` with `contains(key, value)`, `doesNotContain(key)` and `isEmpty()`;
- `ContextAssertions.assertThatViolations()`, on the violations recorded for the running `@BoundrailTest` test, with `hasSize`, `containsKind`
  and `isEmpty`.

Verified by unit tests in `boundrail-context-test`.

**TEST-04** `[M3]` The assertions (TEST-03) SHALL extend AssertJ's `AbstractAssert`. `boundrail-context-test` SHALL declare `junit-jupiter-api`
and `assertj-core` as `api` dependencies, so consumers receive them transitively (DESIGN D40). BuildSmokeIT SHALL check that both appear as
compile/api dependencies in the generated POM and Gradle module metadata of `boundrail-context-test`, as it does for TEST-01.

### Configuration

Properties use the prefix `boundrail.context.`.

Every startup failure caused by invalid configuration also occurs when `spring.main.lazy-initialization` is `true`: an invalid
`executors.modes.<bean-name>` or two keys for one bean name with different modes (EXE-01), an invalid `diagnostics.capture-sites` or
`diagnostics.sample-rate` (DIAG-05), and a Spring field that EXE-10 reads missing at runtime (EXE-10).

| Property | Default | Meaning |
|---|---|---|
| `executors.auto-decorate` | `true` | Decorate Spring task executors (EXE-03) |
| `executors.modes.<bean-name>` | `detached` | Mode of a decorated executor bean, its name matched ignoring case: `attached` or `detached`, matched with Spring Boot's relaxed enum binding; empty or blank means `detached`, and any other value, or two keys for one bean name with different modes, fails startup, even under lazy initialization (EXE-01) |
| `diagnostics.capture-sites` | `off` | `off`, `on` or `sampled`, matched with Spring Boot's relaxed enum binding; empty or blank means `off`, and any other value fails startup, even under lazy initialization (DIAG-05); JVM-wide (RT-08), and applied only when set, so an unset property leaves the current mode unchanged |
| `diagnostics.sample-rate` | `0.01` | Fraction recorded when sampled, from 0.0 to 1.0; any other value fails startup, even under lazy initialization (DIAG-05); JVM-wide (RT-08), and applied only when set; blank counts as not set |
| `audit.enabled` | `true` | Startup executor audit and its log entries (DIAG-06); executor evidence (DIAG-04) does not depend on it |
| `audit.fail-on-gap` | `false` | Fail startup when there are gaps (DIAG-06) |

### Key Entities

- **Executor mode**: `ATTACHED` or `DETACHED`, declared per executor bean.
- **Audit entry**: an executor bean with status `COVERED (mode)` or `GAP`.
- **Capture site**: the first caller frame outside the library where a snapshot was created.
- **Scope listener**: a hook called on every scope entry, whose restorer runs on exit.
- **Recorded violations**: the violations reported in any application context during one `@BoundrailTest` test method (TEST-02).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: RC-9 is green in `integrationTestJdk21`, `integrationTestJdk25` and `integrationTestJdk25ThreadLocal`; IT-1.1 – IT-1.9 are
  enabled and green on JDK 21 and JDK 25.
- **SC-002**: IT-1.1 passes with Micrometer's context-propagating decorator present.
- **SC-003**: With capture sites `off`, no stack walking happens on any code path.

## Assumptions

- 003-http-trust is complete (violations, outbound interceptor, required-key checks).
- `CompletableFuture` default-pool coverage and Reactor are out of scope for v0.1.
- The M2 limitation on Spring MVC async processing ([003-http-trust](../003-http-trust/spec.md) Assumptions; DESIGN Q8) is resolved here by
  IN-09 and EXE-09 (DESIGN D39).
