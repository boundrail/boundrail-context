---
description: "Task list for 004-async-diagnostics"
---

# Tasks: Async Work and Diagnostics

**Input**: Design documents from `/specs/004-async-diagnostics/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [contracts/runtime-listeners.md](contracts/runtime-listeners.md),
[contracts/executors-and-test.md](contracts/executors-and-test.md); 003-http-trust merged (29a42bd)

**Tests**: Required (constitution principle VII). Each task is one red-green cycle: its failing test, with the behaviour it pins down, and the
minimum code that makes it pass, committed and reviewed together. Every task leaves `./gradlew check` green. Every test's display name starts
with the requirement or scenario ID it covers.

- The user approved the plan, these tasks, the TDD exception for the Javadoc-only statements of EXE-04, EXE-07 and DIAG-07, and the ten guard
  tests, and the second TDD exception for TEST-02's Javadoc statement that parallel test methods are not supported (T023)
  ([plan.md](plan.md#tdd-exceptions)). The `null`-restorer test in T003, first accepted as a guard, is red before T003 now that G13 is
  settled, so it is no longer a guard.
- T001 is a spike: no code is kept and nothing is committed ([plan.md](plan.md#spike-t001)). T012, T019 and T021 wait for its outcome, and for
  the user's decision where the outcome needs a spec change.
- Ten guard tests pass before their task: IT-1.4, one unit test each in T008, T011, T015, T016 and T017, and two unit tests each in T020 and
  T023.
  Their red is shown against a temporary code change that is reverted ([plan.md](plan.md#guard-tests)). The user is asked to accept them.
- A test that is green before its task is accepted as a guard when its red is shown against a named temporary change to the finished code,
  which is then reverted; the leader reports it in the task report, the planner adds it to plan.md's Guard Tests table, and the reviewer
  checks it.
- G2 – G14 are resolved in spec.md (D46, DIAG-12). Only G1 is open; it gates T012 and T021 through T001 ([plan.md](plan.md#spec-gaps)).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: The user story the task belongs to (US1: executors and `@Async`; US2: violations and audit; US3: logs; US4: scheduled jobs;
  US5: Spring MVC async)

Path abbreviations:

| Short | Path |
|---|---|
| `core/` | `boundrail-context-core/src/main/java/io/github/boundrail/context/` |
| `coretest/` | `boundrail-context-core/src/test/java/io/github/boundrail/context/` |
| `spring/` | `boundrail-context-spring/src/main/java/io/github/boundrail/context/spring/` |
| `springtest/` | `boundrail-context-spring/src/test/java/io/github/boundrail/context/spring/` |
| `testmod/` | `boundrail-context-test/src/main/java/io/github/boundrail/context/test/` |
| `testmodtest/` | `boundrail-context-test/src/test/java/io/github/boundrail/context/test/` |
| `it/` | `integration-tests/src/test/java/io/github/boundrail/context/it/` |
| `topology/` | `integration-tests/src/test/java/io/github/boundrail/context/it/topology/` |

Verify commands:

- **Unit**: `./gradlew :<project>:test --tests '<TestClass>'` for red and green, then `./gradlew check` before commit.
- **IT**: the scenario run, then `./gradlew check` before commit:

  ```bash
  ./gradlew --continue :integration-tests:integrationTestJdk21 --tests '*ContextPropagationIT' \
            :integration-tests:integrationTestJdk25 --tests '*ContextPropagationIT'
  ```

- **RC**: all three tasks:

  ```bash
  ./gradlew --continue :integration-tests:integrationTestJdk21 --tests '*RuntimeConformanceIT' \
            :integration-tests:integrationTestJdk25 --tests '*RuntimeConformanceIT' \
            :integration-tests:integrationTestJdk25ThreadLocal
  ```

Enabling a scenario means: remove `@Disabled`; keep its `@DisplayName` and the comment with the spec text; write the body. Scenarios assert
deltas, markers or their own extra edge instance, never totals, because the topology is shared.

Tests that change the JVM-wide capture-site mode restore it afterwards; tests that register a `ScopeListener` remove it in `finally`.

## Phase 0: Spike

- [ ] T001 Spike: decorator composition, MVC `Callable` hook, `@Scheduled` hook, mode map keys.
  - Answer the four questions in [plan.md](plan.md#spike-t001) with throwaway `ApplicationContextRunner` tests on Boot 4.1.1 / Framework 7.0.9:
    1. EXE-03: can the existing `TaskDecorator` of each of the three types be read once at post-processing (option A, reflection), and does
       `library ∘ existing` run the existing decorator inside the library's scope, with a Boot-built executor, one with
       `spring.task.execution.propagate-context=true`, and one whose decorator is set in code?
    2. EXE-09: option A (wrap `RequestMappingHandlerAdapter`'s executor) or B (wrap the `Callable` return value), with a `Callable` and a
       `WebAsyncTask` with and without its own executor.
    3. EXE-06: option A (proxy `@Scheduled` beans) or B (subclass the scheduled-annotation post-processor through a
       `BeanDefinitionRegistryPostProcessor`); which gives `<beanName>.<methodName>`.
    4. EXE-01: the key `executors.modes.rawExecutor` from a properties source and from an environment variable.
  - Output: the leader's report. The planner records the chosen mechanisms in plan.md's Research Notes. If EXE-03 composition or the EXE-09
    `WebAsyncTask` clause cannot be met without an approach the user rejects, the spec-creator drafts the spec change and the user decides it
    (G1) before T012 or T021 starts.
  - Not committed, not a red-green cycle.

---

## Phase 1: Setup

None. 001 – 003 built the projects, the integration test tasks, the topology, the auto-configuration and the disabled scenarios. Each dependency
is added in the green step of the first task whose test needs it ([plan.md](plan.md#build)).

---

## Phase 2: Foundational — core runtime hooks (blocks every story)

- [ ] T002 [P] Scope listeners: registration, order and restore.
  - Red, in `coretest/ScopeListenerTest.java`, on the base (ThreadLocal) variant:
    - `RT-07 listeners are entered in registration order after the entered snapshot is current`: two listeners record their name and
      `Context.capture()` in `onEnter`, for `ContextBinding.run` and `call` and `ContextSnapshot.run`, `call` and both `wrap`s;
    - `RT-07 restorers run in reverse order before the previous snapshot is restored`: each restorer records its name and
      `Context.capture()`, which is still the entered snapshot;
    - `RT-07 every nested scope is entered and restored`: three nested bindings give three enter/restore pairs per listener, properly nested;
    - `RT-07 restorers run when the operation throws, and the exception is rethrown unchanged`;
    - `RT-07 a removed listener is no longer called`;
    - `CORE-14 addScopeListener(null) and removeScopeListener(null) throw NullPointerException`.
  - Green: `core/ScopeListener.java` (contract); in `core/ContextRuntime.java` the listener list, `addScopeListener`, `removeScopeListener`
    and package-private `enter(snapshot, op)` with the fast path; `ContextBinding` and `ImmutableContextSnapshot` call `enter`
    ([plan.md](plan.md#core)).
  - Completes: RT-07 (ordering and restore); CORE-14 (listener registration).
  - Verify: Unit `ScopeListenerTest` in `:boundrail-context-core`.
- [ ] T003 Throwing listeners and restorers; enable RC-9.
  - Red:
    - in `ScopeListenerTest`:
      - `RT-07 a listener that throws a RuntimeException is logged and does not change the operation's outcome, and an Error propagates`:
        the first listener's `onEnter` throws `IllegalStateException`; the second is still entered and restored; the operation's result,
        and in a second case its exception, come back unchanged; one `WARNING` record with the thrown exception is captured through a JUL
        `Handler` on `io.github.boundrail.context`. A listener throwing `AssertionError` makes the scope entry throw that `Error`;
      - `RT-07 a restorer that throws a RuntimeException is logged and the remaining restorers still run, and an Error propagates`;
      - `RT-07 adding a registered listener keeps it once in its original position, and removing an unregistered listener does nothing`: A,
        B, A again gives the order A, B with one call each; removing C changes nothing;
      - `RT-07 a listener whose onEnter returns null stops the scope entry: the operation does not run, earlier restorers run in reverse
        order, then NullPointerException is thrown`: listeners A, B, C, where B returns `null`; the operation and C's `onEnter` are not
        called, A's restorer is, and the scope throws `NullPointerException` (CORE-14). Red before T003: T002 runs the operation;
    - enable RC-9 in `it/RuntimeConformanceIT.java`: two listeners registered for the test, removed in `finally`; for nested bindings and a
      `snapshot.call` that throws, their calls (only theirs, as the topology's MDC listeners may be registered) are enter A, enter B, restore
      B, restore A per scope; a third, throwing listener leaves the result unchanged.
  - Green: per-listener and per-restorer `try`/`catch (RuntimeException)` with `System.Logger` in `ContextRuntime.enter`; `addIfAbsent`; a
    `null` restorer stops the entry, unwinds the restorers already returned and throws `NullPointerException`.
  - Completes: RT-07; CORE-14 (`null` restorer); RC-9.
  - Verify: Unit `ScopeListenerTest`; RC (all three tasks).
- [ ] T004 [P] Capture-site mode in the runtime.
  - Red:
    - in `coretest/CaptureSiteModeTest.java`:
      - `RT-08 the mode defaults to off with a sample rate of 0.01`;
      - `RT-08 captureSites(mode, rate) changes the mode and the rate, read back by captureSites() and sampleRate()`;
      - `RT-08 a NaN or out-of-range sample rate throws IllegalArgumentException and leaves the mode unchanged`: `NaN`, `-0.1`, `1.1`,
        infinities; `0.0` and `1.0` are accepted;
      - `CORE-14 captureSites(null, rate) throws NullPointerException`;
    - in `coretest/internal/CaptureSitePropertiesTest.java`, through the package-private parser with a recording `System.Logger`:
      - `RT-08 off, on and sampled are recognised exactly`, and `ON` or `" on"` logs a warning and gives `off`;
      - `RT-08 a sample rate is parsed with Double.parseDouble and accepted from 0.0 to 1.0`: `0`, `1.0`, `0.25`, `" 0.5 "`, `5e-1` and
        `0x1p-1` are recognised; `abc`, `NaN`, `-0.1` and `2` log a warning and give `0.01`.
  - Green: `ContextRuntime.CaptureSites`, the `volatile` mode pair, `captureSites(...)`, `captureSites()`, `sampleRate()`; `core/internal/
    CaptureSiteProperties.java`, read when `ContextRuntime` initialises.
  - Completes: RT-08; CORE-14 (`captureSites`).
  - Verify: Unit `CaptureSiteModeTest` and `CaptureSitePropertiesTest`.
- [ ] T005 Capture sites on snapshots: `on`, `off`, and no walking when off.
  - Red:
    - in `coretest/diagnostics/CaptureSiteTest.java` (package `io.github.boundrail.context.diagnostics`, outside the excluded packages):
      - `DIAG-10 a binding under on records the first frame outside the library, and EMPTY has none`: the site starts with
        `io.github.boundrail.context.diagnostics.CaptureSiteTest.` and is in `StackTraceElement.toString()` form, also when the binding is made
        inside a lambda in the test; `ContextSnapshot.EMPTY.captureSite()` is empty;
      - `DIAG-10 filtering, running and wrapping keep the capture site`: `policy.filter` of a recorded snapshot, and the current snapshot inside
        its `run`, `call` and both `wrap`s, have the same site;
      - `DIAG-05 under off no capture site is recorded`;
    - in `coretest/CaptureSiteRecorderTest.java` (library package, for the seam):
      - `SC-003 with capture sites off no stack walking happens, and with on one walk per binding`: a counting walker replaces the
        recorder's walker for the test; a binding under `off` counts 0, under `on` counts 1.
  - Green: `captureSite()` on `ContextSnapshot`; the field in `ImmutableContextSnapshot`; `core/CaptureSiteRecorder.java`; `ContextBinding.
    applied()` records; `ContextPolicy.filter` keeps ([plan.md](plan.md#core)).
  - Completes: DIAG-10; DIAG-05 (`on`, `off`, core); SC-003.
  - Verify: Unit `CaptureSiteTest` and `CaptureSiteRecorderTest`.
- [ ] T006 Sampled capture sites.
  - Red, in `CaptureSiteTest`:
    - `DIAG-05 sampled at rate 1.0 records every snapshot and at 0.0 none`;
    - `DIAG-05 sampled at 0.5 records some but not all of 1000 snapshots` (bounds wide enough never to flake: between 300 and 700).
  - Green: the `SAMPLED` branch with `ThreadLocalRandom`.
  - Completes: DIAG-05 (`sampled`, core).
  - Verify: Unit `CaptureSiteTest`.

**Checkpoint**: core has listeners, the capture-site mode and capture sites; RC-9 is green on all three tasks.

---

## Phase 3: Foundational — violation evidence and listeners (blocks US2)

- [ ] T007 Evidence filled by the reporter for every adapter.
  - Red, in `springtest/ViolationEvidenceTest.java`, with capture sites `on` for the test (restored after):
    - `DIAG-04 a violation from the client interceptor carries the thread and the capture site of the current snapshot`: inside
      `Context.without(tenant)` in the test, a call through an internal client throws `ContextViolationException` whose violation's thread is
      the current thread and whose capture site is in this test class;
    - `DIAG-04 violations from the inbound filter and from ContextContributions.invalid carry the capture site of their scope`;
    - `DIAG-04 a violation in a scope without a capture site has none`;
    - `DIAG-04 the WARN entry includes the capture site after the thread`, in `ViolationReporterTest`, through `RecordingLog`.
  - Green: `ViolationReporter.violation(kind, key, boundary, destination)`; the inbound filter, the authentication filter's `invalid` and the
    client interceptor build their violations through it; the WARN message appends the capture site ([plan.md](plan.md#spring-wiring)).
  - Completes: DIAG-04 (thread, capture site, every adapter, WARN entry).
  - Verify: Unit `ViolationEvidenceTest` and `ViolationReporterTest` in `:boundrail-context-spring`.
- [ ] T008 Violation listeners.
  - Red, in `springtest/ViolationListenerTest.java` (`ApplicationContextRunner`):
    - `DIAG-11 every ViolationListener bean receives each violation in bean order, after it is counted, also when its log entry is
      rate-limited`: two `@Order`ed listeners; two identical violations; both listeners see both, the counter is already incremented when each
      listener runs, and one WARN entry is logged;
    - `DIAG-11 a throwing listener is logged at WARN with the exception, the remaining listeners still run, and the outcome is unchanged`: the
      WARN entry carries the thrown exception (`RecordingLog`); in `fail` mode the interceptor
      still throws the `ContextViolationException` for the violation, not the listener's exception;
    - `RT-09 violation log rate limiting belongs to one application context`: two contexts each log their first identical violation. This is
      a guard: show it red with `ViolationReporter.lastLogged` temporarily made `static`, then revert ([plan.md](plan.md#guard-tests)).
  - Green: `spring/ViolationListener.java` (contract); the reporter takes `ObjectProvider<ViolationListener>` and calls them after counting
    and logging.
  - Completes: DIAG-11; RT-09 (reporting per context).
  - Verify: Unit `ViolationListenerTest`.

**Checkpoint**: every violation carries the observed thread and capture site and reaches `ViolationListener` beans.

---

## Phase 4: User Story 1 - Context survives Spring executors and `@Async` (Priority: P1) 🎯 MVP

**Goal**: Work handed to Spring's executors, `@Async` methods and `BoundExecutors` runs in the submitter's context, filtered by mode.

**Independent Test**: IT-1.1 and IT-1.3 (IT-1.3 also needs US2's audit and is enabled in T015).

- [ ] T009 [P] [US1] `BoundExecutors` for `Executor`.
  - Red, in `coretest/BoundExecutorsTest.java`:
    - `EXE-02 a task runs in the snapshot captured at submission, filtered for the mode`: a key that propagates to `ATTACHED` only is present
      under `attached(...)` and absent under `detached(...)`; a binding changed after submission is not seen;
    - `EXE-04 overloads without a policy filter with BuiltInKeys.defaultPolicy()`: a key an application policy restricts to `ATTACHED` still
      reaches `detached(executor)` (unregistered keys propagate to both), and does not reach `detached(executor, policy)`;
    - `EXE-02 the worker thread is left with no context after the task`;
    - `CORE-14 BoundExecutors rejects null`: each `Executor` overload with a `null` delegate or policy.
  - Green: `core/BoundExecutors.java` with the four `Executor` overloads and a package-private wrapper; Javadoc for EXE-04 (default policy),
    EXE-07 and DIAG-07 (TDD exception, reviewed).
  - Completes: EXE-02 and EXE-04 (`Executor`); EXE-07; DIAG-07; CORE-14 (`Executor` overloads).
  - Verify: Unit `BoundExecutorsTest`.
- [ ] T010 [US1] `BoundExecutors` for `ExecutorService`.
  - Red, in `BoundExecutorsTest`:
    - `EXE-02 submit, invokeAll and invokeAny run each task in the snapshot captured at submission`, for `Runnable` and `Callable`;
    - `EXE-04 lifecycle methods delegate`: `shutdown`, `shutdownNow`, `isShutdown`, `isTerminated`, `awaitTermination`, `close`;
    - `CORE-14 the ExecutorService overloads reject null`, and `invokeAll` with a `null` element.
  - Green: the `ExecutorService` overloads and wrapper.
  - Completes: EXE-02 and EXE-04 (`ExecutorService`); CORE-14 (`ExecutorService` overloads).
  - Verify: Unit `BoundExecutorsTest`.
- [ ] T011 [US1] Auto-decoration of Spring executors; enable IT-1.1.
  - Red:
    - in `springtest/ExecutorDecorationTest.java` (`ApplicationContextRunner`):
      - `EXE-03 ThreadPoolTaskExecutor, SimpleAsyncTaskExecutor and ThreadPoolTaskScheduler beans are decorated before initialisation, and
        none is with auto-decorate=false`: a task submitted from inside a binding sees the binding, for each type, including a scheduled task
        on the scheduler;
      - `EXE-02 decorated executors filter with the effective policy for detached`: a `ContextPolicy` bean key that does not propagate to
        `DETACHED` is absent in the task;
      - `EXE-04 a plain Executor or ExecutorService bean is not wrapped automatically`: the bean is the same instance and a task sees no
        context. This is a guard: show it red with the post-processor temporarily replacing every other `ExecutorService` bean with
        `BoundExecutors.detached(bean)`, then revert ([plan.md](plan.md#guard-tests));
      - `EXE-03 the decoration does not create ContextPolicy beans during post-processor registration`: a `ContextPolicy` bean's factory
        method records its call; it is called after the executor beans are post-processed, not before the post-processor is registered;
    - enable IT-1.1: a public request with alice's JWT to edge runs `EdgeAsyncWork`'s `@Async` method on `taskExecutor`, which calls orders;
      orders records `ctx.tenant=acme` (marker header).
  - Green:
    - `spring/ExecutorDecorator.java` and `spring/ExecutorDecoratingPostProcessor.java` (`static` bean method), `executors.auto-decorate` in
      `BoundrailContextProperties`, always `DETACHED` for now; it sets the decorator (composition comes in T012);
    - topology test code: edge's `taskExecutor` from `ThreadPoolTaskExecutorBuilder` with one thread, `spring.task.execution.propagate-context
      =true`, `@EnableAsync`, `topology/EdgeAsyncWork.java`, the endpoint; `micrometer-context-propagation` as `testImplementation` in
      `integration-tests`, with a catalog entry.
  - Completes: EXE-03 (installation, three types, `auto-decorate`); EXE-02 (decorated, effective policy); EXE-04 (not wrapped); EXE-05
    (detached); IT-1.1.
  - Verify: Unit `ExecutorDecorationTest`; IT.
- [ ] T012 [US1] Composition with an existing decorator. Needs T001's outcome (and the user's decision, if G1 applies).
  - Red, in `ExecutorDecorationTest`, for each of the three types:
    - `EXE-03 an existing decorator set in code runs inside the library's scope`: the existing decorator records `Context.capture()` when its
      decorated runnable runs, and sees the submitter's binding;
    - `EXE-03 a decorator applied by Boot's builder from TaskDecorator beans is kept and runs inside the library's scope`;
    - `EXE-03 Micrometer's ContextPropagatingTaskDecorator still propagates its thread locals`: with a registered `ThreadLocalAccessor`, both
      its value and the context reach the task (`micrometer-context-propagation` as `testImplementation` in `-spring`);
    - if the spike chose reflection: `EXE-03 composition fails loudly if the framework's decorator field is missing`, against a subclass
      lookup that names a missing field.
  - Green: compose `library ∘ existing` in the post-processor, per the spike.
  - Completes: EXE-03 (composition, library outermost); SC-002 (with IT-1.1).
  - Verify: Unit `ExecutorDecorationTest`; IT (IT-1.1 stays green).
- [ ] T013 [US1] Executor modes.
  - Red, in `springtest/ExecutorModeTest.java`:
    - `EXE-01 executors.modes.<bean-name>=attached makes that executor attached, and others stay detached`: an `ATTACHED`-only key reaches
      the attached executor's task and not another's;
    - `EXE-01 modes are matched with relaxed enum binding and a blank value means detached`: `Attached`, `ATTACHED` and ` attached ` are
      attached; `` (through a `MapPropertySource`) is detached;
    - `EXE-01 an invalid mode fails startup, also with lazy initialization`: `bogus`, with and without the T006a lazy setup from 003;
    - `EXE-05 an @Async method on an attached executor runs with the attached context`;
    - `EXE-01 the bean name is matched ignoring case`: `executors.modes.rawexecutor=attached` from a `MapPropertySource`, and the
      environment variable form `BOUNDRAIL_CONTEXT_EXECUTORS_MODES_RAWEXECUTOR=attached` through a `SystemEnvironmentPropertySource`, make
      the decorated bean `rawExecutor` attached;
    - `EXE-01 executors.modes applies to decorated beans only`: with `attached` set for a decorated bean, a `BoundExecutors.detached` bean and
      a plain `ExecutorService` bean, the decorated bean's task gets the `ATTACHED`-only key, the `BoundExecutors` bean's task does not, and
      the plain bean is still not wrapped;
    - `EXE-01 two modes keys equal ignoring case with different modes fail startup, also with lazy initialization, and the same mode under
      both is accepted`: `rawExecutor=attached` with `rawexecutor=detached` (through a `MapPropertySource`) fails with and without the T006a
      lazy setup from 003, naming both keys; `rawExecutor=attached` with `RAWEXECUTOR=Attached` starts, and the bean is attached.
  - Green: `Executors.modes` in `BoundrailContextProperties`, whose compact constructor rejects keys equal ignoring case with different modes;
    the post-processor looks up the bean's mode ignoring case.
  - Completes: EXE-01; EXE-02 (`ATTACHED`); EXE-05.
  - Verify: Unit `ExecutorModeTest`.

**Checkpoint**: IT-1.1 green on JDK 21 and JDK 25.

---

## Phase 5: User Story 2 - Lost context fails loudly, with evidence (Priority: P1)

**Goal**: Violations name the executor a thread belongs to; the startup audit lists covered executors and gaps, and can fail startup.

**Independent Test**: IT-1.2 – IT-1.5 in the IT command; IT-1.8 is enabled with the test module in T023.

- [ ] T014 [US2] Executor evidence; enable IT-1.2 and IT-1.4 (guard).
  - Red:
    - in `springtest/ExecutorTableTest.java`:
      - `DIAG-04 a violation on a thread of an executor bean names the bean and its status`: on a `rawExecutor-1` thread of a plain bean,
        `rawExecutor (GAP)`; on a thread of a decorated `ThreadPoolTaskExecutor` with prefix `work-`, `<bean> (COVERED detached)`, and
        `attached` with that mode;
      - `DIAG-04 the longest matching prefix wins`: beans with prefixes `task-` and `task-io-`; thread `task-io-1`;
      - `DIAG-04 executor evidence is present once the context has started and absent before`: a violation reported from a bean's
        initialisation has none, one after refresh has it;
      - `DIAG-04 the WARN entry includes the executor evidence after the capture site`;
      - `DIAG-12 the table covers only instantiated singleton executor beans of the context itself, and instantiates none`: a singleton
        has evidence; a `@Lazy` executor bean not yet requested, a prototype executor bean and an executor bean of a parent context give no
        evidence on threads named after them, and the lazy and prototype factory methods were never called;
    - enable IT-1.2: on the shared edge (`fail` mode), the `rawExecutor` endpoint's work completes exceptionally with
      `ContextViolationException`: kind `MISSING_REQUIRED`, key `tenant`, boundary `INTERNAL_CALL`, executor evidence `rawExecutor (GAP)`;
      orders records nothing with the marker;
    - enable IT-1.4 with an extra edge started with `boundrail.context.violations.mode=report`: the work runs twice; orders records both
      without `tenant-id`; the extra edge's counter `{kind=missing_required,key=tenant,boundary=internal_call}` rises by two; one WARN entry
      is logged (`OutputCaptureExtension`). This is a guard: show it red with `ViolationReporter.dueForLog` temporarily returning `true`
      always, then revert ([plan.md](plan.md#guard-tests)).
  - Green:
    - `spring/ExecutorTable.java`, filled on `ContextRefreshedEvent`; the post-processor records decorated names and modes; the reporter
      fills `executorEvidence` and logs it;
    - topology test code: `rawExecutor` and its endpoint; `Topology.Running.startEdge(String...)`.
  - Completes: DIAG-04 (executor evidence, longest prefix, before start, WARN entry); DIAG-12 (table); IT-1.2; IT-1.4.
  - Verify: Unit `ExecutorTableTest`; IT.
- [ ] T015 [US2] Startup audit and fail-on-gap; enable IT-1.3 and IT-1.5.
  - Red:
    - in `springtest/ExecutorAuditTest.java` (an `ApplicationReadyEvent` published by the test, `RecordingLog`):
      - `DIAG-06 every executor bean is logged in bean-name order, then the summary`: `Executor audit: aExecutor COVERED (attached)` at INFO,
        `Executor audit: bExecutor GAP` at WARN, `Executor audit: cExecutor COVERED (detached)` at INFO for a `BoundExecutors.detached` bean
        (with `executors.modes.cExecutor=attached` set, which EXE-01 does not apply to it),
        then `Executor audit: 2 covered, 1 gaps` at INFO;
      - `DIAG-06 with auto-decorate=false the Spring executor types are gaps`;
      - `DIAG-12 the audit lists only instantiated singleton executor beans of the context itself, and instantiates none`: a lazy executor
        bean not yet requested, a prototype one and a parent context's one are not listed or counted, and their factory methods were never
        called;
      - `DIAG-06 with audit.fail-on-gap=true and a gap startup fails with a message listing every gap`; with no gap it starts;
      - `DIAG-06 with audit.enabled=false nothing is audited and executor evidence is still filled`. This is a guard: show it red with the
        `audit.enabled` condition temporarily removed, then revert ([plan.md](plan.md#guard-tests));
    - enable IT-1.3: on the shared edge, `boundExecutor`'s work reaches orders with `tenant=acme`; an extra edge started in the test logs
      `Executor audit: boundExecutor COVERED (detached)`;
    - enable IT-1.5: an extra edge logs `Executor audit: taskExecutor COVERED (detached)` and `Executor audit: rawExecutor GAP`; an extra edge
      with `boundrail.context.audit.fail-on-gap=true` fails to start, with `rawExecutor` in the message.
  - Green:
    - `core/internal/ModeExecutor.java`, implemented by the `BoundExecutors` wrappers;
    - `spring/ExecutorAudit.java`; `audit.enabled` and `audit.fail-on-gap` in `BoundrailContextProperties`;
    - topology test code: `boundExecutor` and its endpoint.
  - Completes: DIAG-06; DIAG-12 (audit); EXE-01 (a `BoundExecutors` bean keeps its own mode); EXE-04 (recognised by the audit); IT-1.3;
    IT-1.5. The Javadoc of `BoundExecutors` gains DIAG-07's mention of which beans the audit sees (DIAG-12), under the approved exception.
  - Verify: Unit `ExecutorAuditTest`; IT.
- [ ] T016 [P] [US2] Capture-site properties.
  - Red, in `springtest/CaptureSiteSettingsTest.java` (mode restored after each test):
    - `DIAG-05 capture-sites set in the environment is applied and an unset sample-rate keeps the current rate`;
    - `DIAG-05 sample-rate set alone keeps the current mode`;
    - `DIAG-05 capture-sites is matched with relaxed enum binding and a blank value means off`: `ON`, ` Sampled `; `` through a
      `MapPropertySource`;
    - `DIAG-05 an invalid capture-sites or sample-rate fails startup, also with lazy initialization`: `bogus`, `NaN`, `-0.1`, `1.5`, `abc`;
    - `DIAG-05 a context that sets neither property leaves the JVM-wide mode unchanged`: after `captureSites(ON, 0.5)`, a context starts and
      the mode is still `ON` at `0.5`. This is a guard: show it red with the bean temporarily applying `off` and `0.01` when unset, then
      revert ([plan.md](plan.md#guard-tests));
    - `DIAG-05 a blank sample-rate counts as not set`: after `captureSites(OFF, 0.5)`, a context with `capture-sites=on` and `sample-rate=`
      (through a `MapPropertySource`) leaves the mode `ON` at `0.5`, and a context with only a blank `sample-rate` starts and changes
      nothing.
  - Green: `spring/CaptureSiteSettings.java`, a `@Lazy(false)` bean ([plan.md](plan.md#spring-wiring)).
  - Completes: DIAG-05 (Spring properties).
  - Verify: Unit `CaptureSiteSettingsTest`.

**Checkpoint**: IT-1.1 – IT-1.5 green on JDK 21 and JDK 25.

---

## Phase 6: User Story 3 - Logs carry context (Priority: P2)

**Goal**: Loggable keys are in the MDC for exactly the duration of every scope.

**Independent Test**: RC-9 (T003) and IT-1.6.

- [ ] T017 [US3] MDC bridge; enable IT-1.6.
  - Red:
    - in `springtest/MdcScopeListenerTest.java` (`ApplicationContextRunner`, with the context closed in each test):
      - `LOG-01 a bound loggable key is in the MDC for the scope, encoded with its codec, and the previous value is restored`: `tenant`
        (string codec) and a loggable `long` key; an application MDC entry `tenant=outer` is back after the scope; a name absent before is
        removed;
      - `LOG-01 a loggable key without a codec is written with toString()`: the built-in `INITIATOR` under `boundrail.initiator`;
      - `LOG-02 AUTHORITY and DEADLINE are never written to the MDC while loggable keys are`: a snapshot binding `tenant`, `AUTHORITY` and
        `DEADLINE` writes only `tenant`;
      - `LOG-01 without SLF4J no MDC listener is registered` (`FilteredClassLoader(MDC.class)`). This is a guard: show it red with
        `@ConditionalOnClass` temporarily removed from the MDC configuration, then revert ([plan.md](plan.md#guard-tests));
    - enable IT-1.6: inside the IT-1.1 work a log line written through SLF4J contains `tenant=acme`; a later task on the same `taskExecutor`
      thread, submitted from a thread with no context, logs no tenant (`OutputCaptureExtension`).
  - Green: `spring/MdcScopeListener.java` in a nested `@ConditionalOnClass(MDC.class)` configuration; `slf4j-api` as `compileOnly` (catalog
    entry); topology test code: the console pattern with `tenant=%X{tenant}` on every service and the log line in `EdgeAsyncWork`. Run
    TrustBoundaryIT too, whose IT-2.8 inspects log lines.
  - Completes: LOG-01 (single context); LOG-02; IT-1.6 (with RT-03).
  - Verify: Unit `MdcScopeListenerTest`; IT, plus `--tests '*TrustBoundaryIT'` on both tasks.
- [ ] T018 [US3] MDC across nested scopes and several contexts.
  - Red, in `MdcScopeListenerTest`:
    - `LOG-01 a nested Context.without(tenant) removes tenant from the MDC and restores it on exit`;
    - `LOG-01 each application context's listener writes only its own policy's loggable keys`: two contexts, each with one loggable key;
    - `LOG-01 the listener is removed when its context closes`: after `close()`, a scope writes nothing for that context's keys.
  - Green: removal of unbound names on entry; `DisposableBean.destroy()` removes the listener.
  - Completes: LOG-01 (nested scopes, several contexts, removal; D44).
  - Verify: Unit `MdcScopeListenerTest`.

**Checkpoint**: IT-1.6 green on JDK 21 and JDK 25.

---

## Phase 7: User Story 4 - Scheduled jobs get a system context (Priority: P3)

- [ ] T019 [US4] `@Scheduled` system context; enable IT-1.7. Needs T001's outcome.
  - Red:
    - in `springtest/ScheduledContextTest.java` (`ApplicationContextRunner` with `@EnableScheduling` and a fast `fixedDelay`):
      - `EXE-06 a @Scheduled method invoked by the scheduler runs with exactly initiator=system:scheduled:<beanName>.<methodName>, and a
        direct call keeps the caller's context`: bean `cleanupJob`, method `run`; the test then calls `cleanupJob.run()` inside a binding of
        `tenant`, which the method sees, with no initiator;
      - `EXE-06 a @Scheduled method never sees context left over from another task`: the scheduler is a one-thread `ThreadPoolTaskScheduler`
        whose decorator captured a binding at scheduling (the task scheduled from inside a binding), and the method still sees only the
        initiator;
      - `DIAG-05 under on the @Scheduled binding records a capture site in the library's scheduled support`;
    - enable IT-1.7: edge's `cleanupJob.run` records exactly `initiator=system:scheduled:cleanupJob.run` to `ObservedContext` (bounded poll).
  - Green: the mechanism chosen in T001; topology test code: `@EnableScheduling` and `topology/CleanupJob.java`.
  - Completes: EXE-06; IT-1.7.
  - Verify: Unit `ScheduledContextTest`; IT.

---

## Phase 8: User Story 5 - Spring MVC async requests keep the request's context (Priority: P2)

- [ ] T020 [US5] `ASYNC` dispatches re-enter the request's snapshot.
  - Red, in `springtest/AsyncDispatchTest.java` (`MockMvc` with the library's filters, `asyncDispatch`; `WebApplicationContextRunner` for
    the registration):
    - `IN-09 the inbound filter is registered for REQUEST and ASYNC dispatches`;
    - `IN-09 an ASYNC dispatch runs the rest of the chain in the stored snapshot without asking the verifier, decoding or reporting`: a trusted
      request binds `tenant`; a `DeferredResult` completed from a thread with no context; on the async dispatch the handler sees `tenant`, the
      verifier was called once, and no violation was reported for an untrusted header on the second dispatch;
    - `IN-09 the stored snapshot is the innermost scope's`: with `ContextAuthenticationFilter` contributing a key, the async dispatch sees it,
      and contributors were invoked once;
    - `IN-09 ContextAuthenticationFilter does not run on ASYNC dispatches`. This is a guard: show it red with `shouldNotFilterAsyncDispatch()`
      temporarily returning `false` in `ContextAuthenticationFilter`, then revert ([plan.md](plan.md#guard-tests));
    - `IN-09 an ASYNC dispatch with no stored snapshot runs in the current scope`. This is a guard: show it red with the inbound filter
      temporarily running the chain in `ContextSnapshot.EMPTY` when no snapshot is stored, then revert ([plan.md](plan.md#guard-tests)).
  - Green: the dispatcher types; `shouldNotFilterAsyncDispatch()`; the stored attribute in both filters; the async branch in
    `ContextInboundFilter`; `spring-webmvc` as `testImplementation` (catalog entry).
  - Completes: IN-09.
  - Verify: Unit `AsyncDispatchTest`.
- [ ] T021 [US5] Handler `Callable`s run in the request's snapshot, filtered for `ATTACHED`; enable IT-1.9. Needs T001's outcome (and the
  user's decision, if G1 applies).
  - Red:
    - in `springtest/MvcCallableTest.java` (`MockMvc`):
      - `EXE-09 a Callable returned by a handler runs in the request's snapshot filtered for attached`: an `ATTACHED`-only key is present and a
        key that does not propagate to `ATTACHED` is absent;
      - `EXE-09 this holds whatever the mode of MVC's executor`: MVC's executor is a library-decorated `ThreadPoolTaskExecutor` in `detached`
        mode;
      - `EXE-09 a WebAsyncTask without its own executor runs in the same snapshot, and keeps its timeout and callbacks`;
    - enable IT-1.9: alice's request to edge's `Callable` endpoint reaches orders with `tenant=acme`; for the `DeferredResult` endpoint, the
      edge `HandlerInterceptor` records `tenant=acme` in `postHandle` of the async dispatch.
  - Green: the mechanism chosen in T001 (`spring-webmvc` as `compileOnly`, behind `@ConditionalOnClass`); topology test code: the two
    endpoints and the interceptor.
  - Completes: EXE-09; IT-1.9 (with IN-09).
  - Verify: Unit `MvcCallableTest`; IT.

**Checkpoint**: IT-1.7 and IT-1.9 green on JDK 21 and JDK 25.

---

## Phase 9: Test support (US2's IT-1.8)

- [ ] T022 [P] `SnapshotAssert` and the test module's API dependencies.
  - Red:
    - in `testmodtest/SnapshotAssertTest.java`:
      - `TEST-03 assertThat(snapshot) checks contains, doesNotContain and isEmpty`, each passing and failing (`assertThatThrownBy` with
        `AssertionError`);
      - `TEST-04 SnapshotAssert extends AbstractAssert`;
      - `CORE-14 ContextAssertions.assertThat(null) and contains(null, value) throw NullPointerException`;
    - in `it/BuildSmokeIT.java`: `TEST-04 junit-jupiter-api and assertj-core are api dependencies of boundrail-context-test`, in the POM
      (scope `compile`) and in `module.json` (`apiElements`).
  - Green: `testmod/ContextAssertions.java` (`assertThat`), `testmod/SnapshotAssert.java`; in `boundrail-context-test/build.gradle.kts`,
    `api` on `junit-jupiter-api` (catalog entry) and `assertj-core`, and `versionMapping` if the POM needs it ([plan.md](plan.md#build)).
  - Completes: TEST-03 (`SnapshotAssert`); TEST-04; CORE-14 (`SnapshotAssert`).
  - Verify: Unit `SnapshotAssertTest` in `:boundrail-context-test`; `./gradlew check` (BuildSmokeIT).
- [ ] T023 `@BoundrailTest` and the violation recorder; enable IT-1.8.
  - Red:
    - in `testmodtest/BoundrailTestExtensionTest.java` (JUnit Platform `EngineTestKit` running sample test classes):
      - `TEST-02 @BoundrailTest turns capture sites on for the class and restores the previous mode and rate afterwards`: from `SAMPLED` at
        `0.2`, `ON` inside every test, `SAMPLED` at `0.2` after;
      - `TEST-02 violations reported in any application context during a test method are recorded, and nothing outside one`: two contexts
        report inside the test; one reports before it and between tests; `RecordedViolations.all()` holds exactly the inside ones, in order;
      - `TEST-02 RecordedViolations.all() called while no @BoundrailTest test method is running throws IllegalStateException`: the instance
        a test received is called again from `@AfterAll`;
      - `TEST-02 the test module's only auto-configuration is the recorder's ViolationListener`: `ImportCandidates.load` for the `-test`
        imports file lists exactly `BoundrailTestAutoConfiguration`, and an `ApplicationContextRunner` with it has one `ViolationListener` bean
        and no other bean of the module;
      - `TEST-02 the extension does not change the violation mode`: a context started inside the class still throws in `fail` mode. This is a
        guard: show it red with the extension temporarily setting the system property `boundrail.context.violations.mode=report` before the
        class and clearing it after, then revert ([plan.md](plan.md#guard-tests));
    - rewrite `TEST-01 the verifier is not auto-configured` in `testmodtest/TestInboundTrustVerifierTest.java` as `TEST-01 neither the
      verifier nor peer() is auto-configured`: the `-test` auto-configuration registers no `InboundTrustVerifier` bean. This is a guard: show
      it red with a `TestInboundTrustVerifier` `@Bean` temporarily added to that auto-configuration, then revert
      ([plan.md](plan.md#guard-tests));
    - rewrite BuildSmokeIT's `TEST-01 the boundrail-context-test JAR ships no AutoConfiguration.imports and no spring.factories` as `TEST-01
      the boundrail-context-test JAR auto-configures only the TEST-02 violation recorder`: its imports file lists exactly
      `BoundrailTestAutoConfiguration`, and there is no `spring.factories` (red before T023: there is no imports file);
    - enable IT-1.8: `@BoundrailTest` on `ContextPropagationIT`; the `/call-orders/without-tenant` violation's capture site starts with
      `io.github.boundrail.context.it.topology.EdgeApp.`; the IT-1.2 endpoint's violation has no capture site.
  - Green: `testmod/BoundrailTest.java` (its Javadoc says parallel test methods are not supported: TDD exception awaiting approval),
    `BoundrailTestExtension.java`, `RecordedViolations.java`, `ViolationRecorder.java`, `BoundrailTestAutoConfiguration.java` and its imports
    file; `compileOnly` on `spring-boot-autoconfigure` in `-test`.
  - Completes: TEST-02; RT-09 (the test module's exception, D40); DIAG-04, DIAG-05 and DIAG-10 end to end; IT-1.8.
  - Verify: Unit `BoundrailTestExtensionTest` and `TestInboundTrustVerifierTest`; IT; `./gradlew check` (BuildSmokeIT).
- [ ] T024 `ViolationsAssert`.
  - Red, in `testmodtest/ViolationsAssertTest.java` (through `EngineTestKit` sample classes under `@BoundrailTest`):
    - `TEST-03 assertThatViolations() checks hasSize, containsKind and isEmpty on the running test's violations`, each passing and failing;
    - `TEST-04 ViolationsAssert extends AbstractAssert`;
    - `CORE-14 containsKind(null) throws NullPointerException`;
    - `TEST-02 assertThatViolations() called while no @BoundrailTest test method is running throws IllegalStateException`: from a plain
      test, and from `@AfterAll` of a `@BoundrailTest` class.
  - Green: `testmod/ViolationsAssert.java` and `assertThatViolations()` in `ContextAssertions`.
  - Completes: TEST-03; TEST-02 (`assertThatViolations()` outside a test method); CORE-14 (`ViolationsAssert`).
  - Verify: Unit `ViolationsAssertTest`.

**Checkpoint**: IT-1.1 – IT-1.9 green on JDK 21 and JDK 25; RC-9 green on all three tasks.

---

## Phase 10: Close

- [ ] T025 Verify each requirement one by one against the code and its tests, using the coverage table in
  [plan.md](plan.md#requirement-coverage):
  - Requirements: CORE-14, RT-07 – RT-09, IN-09, EXE-01 – EXE-07, EXE-09, DIAG-04 – DIAG-07, DIAG-10 – DIAG-12, LOG-01, LOG-02 and
    TEST-02 – TEST-04.
  - SC-001: RuntimeConformanceIT and ContextPropagationIT have no `@Disabled` left; RC-9 passes in all three tasks, IT-1.1 – IT-1.9 in
    `integrationTestJdk21` and `integrationTestJdk25`.
  - SC-002: IT-1.1 runs with Micrometer's decorator configured on `taskExecutor`, and T012's test shows it still works.
  - SC-003: T005's walker test; by inspection, the `OFF` path in `CaptureSiteRecorder` returns before any `StackWalker` call.
  - Every requirement has a test whose display name starts with its ID, except the Javadoc statements of the TDD exception: check the Javadoc
    of `BoundExecutors` against EXE-04 (default policy), EXE-07 and DIAG-07 (including DIAG-12's beans), and the Javadoc of `@BoundrailTest`
    against TEST-02 (parallel test methods), word for word in meaning.
  - `boundrail-context-core` still has no runtime dependencies
    (`./gradlew :boundrail-context-core:dependencies --configuration runtimeClasspath`).
  - `-spring` declares SLF4J and Spring MVC only as `compileOnly`
    (`./gradlew :boundrail-context-spring:dependencies --configuration runtimeClasspath`).
  - By inspection (RT-09): core's only static mutable state is the slot, the listener list and the capture-site pair; `-spring` has none;
    `-test` has only the recorder. No adapter uses `CallableProcessingInterceptor.preProcess`/`postProcess` or another set/restore hook. No
    MDC call other than `get`, `put` and `remove`.

  Run `./gradlew check` fresh, which covers JDK 21, JDK 25 and the forced ThreadLocal run. With the CI run green, this closes the feature.

## Dependencies & Execution Order

- T001 runs first, in parallel with Phase 2. T012, T019 and T021 need its outcome; T012 and T021 also need the user's decision if G1 applies.
- Phase 2: T002 → T003. T004 is independent of T002. T005 needs T004; T006 needs T005.
- Phase 3: T007 needs T005. T008 needs T007.
- US1: T009 needs nothing from this feature; T010 needs T009. T011 needs T002; T012 needs T001 and T011; T013 needs T011.
- US2: T014 needs T007, T010 and T013. T015 needs T014. T016 needs T004 and T006, and is independent of the executor tasks.
- US3: T017 needs T003 and T011 (IT-1.6 uses the IT-1.1 work). T018 needs T017.
- US4: T019 needs T001, T005 and T011.
- US5: T020 needs T002. T021 needs T001, T020 and T013.
- Test support: T022 is independent. T023 needs T005, T008, T014 and T022. T024 needs T023.
- T025 comes last.
- Tasks touching the same file run in sequence (`BoundrailContextAutoConfiguration`, `BoundrailContextProperties`, `EdgeApp` and
  `ContextPropagationIT` are touched by many).
- Every task is committed and reviewed on its own, on branch `004-async-diagnostics`.

## Exit Checklist

- [ ] RC-9 enabled and green on all three JDK tasks
- [ ] Scenarios enabled and green on JDK 21 and JDK 25: IT-1.1 – IT-1.9
- [ ] Requirements implemented with tests named after their IDs (roadmap traceability row M3): CORE-14, RT-07 – RT-09, IN-09,
  EXE-01 – EXE-07, EXE-09, DIAG-04 – DIAG-07, DIAG-10 – DIAG-12, LOG-01 – LOG-02, TEST-02 – TEST-04; the Javadoc statements of EXE-04,
  EXE-07, DIAG-07 and TEST-02 (parallel runs) checked by inspection (TDD exceptions)
- [ ] T001's outcome recorded in plan.md, and any spec change it needed decided by the user and committed
- [ ] Guard tests (IT-1.4, and the unit guards in T008, T011, T015, T016, T017, T020 (two) and T023 (two)) shown red against their
  temporary change, and the change reverted
- [ ] G1 settled by T001 (and, if needed, the user's spec decision), and T012 and T021 written accordingly
- [ ] `boundrail-context-core` still has no runtime dependencies; SLF4J and Spring MVC are `compileOnly` in `-spring`
- [ ] `./gradlew check` green
