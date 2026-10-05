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

- The user approved the plan, these tasks, the TDD exception for the Javadoc-only statements of EXE-04, EXE-07 and DIAG-07, and the guard
  tests, and the second TDD exception for TEST-02's Javadoc statement that parallel test methods are not supported (T023)
  ([plan.md](plan.md#tdd-exceptions)). The `null`-restorer test in T003, first accepted as a guard, is red before T003 now that G13 is
  settled, so it is no longer a guard.
- T001, the spike, is done; its outcome and the user's decisions (EXE-10, amended EXE-06 and EXE-07, D47) are in
  [plan.md](plan.md#spike-t001).
- Eleven guard tests pass before their task: IT-1.4, one unit test each in T003 (new), T008, T011, T015, T016 and T017, and two
  unit tests each in T020 and T023.
  Their red is shown against a temporary code change that is reverted ([plan.md](plan.md#guard-tests)).
- A test that is green before its task is accepted as a guard when its red is shown against a named temporary change to the finished code,
  which is then reverted; the leader reports it in the task report, the planner adds it to plan.md's Guard Tests table, and the reviewer
  checks it.
- Every spec gap is closed: G2 – G14 (D46, DIAG-12) and G1 (EXE-10, D47) ([plan.md](plan.md#spec-gaps)).
- All eleven guard tests, including the one added in T003, are accepted by the user ([plan.md](plan.md#guard-tests)).

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

- [x] T001 Spike: decorator composition, MVC `Callable` hook, `@Scheduled` hook, mode map keys.
  - Done; no code kept, nothing committed. Outcome in [plan.md](plan.md#spike-t001): EXE-03 reads each executor's private `taskDecorator`
    and composes `library ∘ existing` (option A); EXE-09 reads `RequestMappingHandlerAdapter`'s private `taskExecutor` and wraps it (option A);
    EXE-06 replaces Spring's own `internalScheduledAnnotationProcessor` definition with a subclass (option B), keeping the bean name in an
    instance `ThreadLocal`, since `createRunnable`'s third argument is the scheduler qualifier; `executors.modes` is bound as
    `Map<String, String>`. The user's decisions are in spec.md as EXE-10 and amended EXE-06 and EXE-07 (D47); G1 is closed.

---

## Phase 1: Setup

None. 001 – 003 built the projects, the integration test tasks, the topology, the auto-configuration and the disabled scenarios. Each dependency
is added in the green step of the first task whose test needs it ([plan.md](plan.md#build)).

---

## Phase 2: Foundational — core runtime hooks (blocks every story)

- [x] T002 [P] Scope listeners: registration, order and restore.
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
- [x] T003 Throwing listeners and restorers; enable RC-9.
  - Red:
    - in `ScopeListenerTest`:
      - `RT-07 a listener that throws a RuntimeException is logged and does not change the operation's outcome, and an Error propagates`:
        the first listener's `onEnter` throws `IllegalStateException`; the second is still entered and restored; the operation's result,
        and in a second case its exception, come back unchanged; one `WARNING` record with the thrown exception is captured through a JUL
        `Handler` on `io.github.boundrail.context`. A listener throwing `AssertionError` makes the scope entry throw that `Error`;
      - `RT-07 an Error from a later onEnter stops the entry: the operation and later listeners do not run, earlier restorers run in reverse
        order, and the Error propagates`: listeners A, B, C, D, where C throws `AssertionError`; D's `onEnter` and the operation are not
        called, B's then A's restorers are, and the scope throws the same `AssertionError` (settled by the user during T003's review). This is
        a guard (accepted by the user): T003's code already unwinds on any throwable. Show it red with the unwinding of the
        restorers already returned temporarily limited to the `null`-restorer path, so an `Error` skips it, then revert
        ([plan.md](plan.md#guard-tests));
      - `RT-07 a restorer that throws a RuntimeException is logged naming the listener that returned it, and the remaining restorers still
        run, while an Error from a restorer propagates at once and the remaining restorers are skipped`: listeners A, B, C; B's restorer throws
        `IllegalStateException`: one `WARNING` record whose message names listener B, not the restorer's class, and A's restorer still runs;
        in a second case B's restorer throws `AssertionError`: it propagates, and A's restorer is not called. Red before the fix: T003's code
        names the restorer's class (the `Error` part already holds and sits with that red assertion, so it is not a guard);
      - `RT-07 adding a registered listener keeps it once in its original position, and removing an unregistered listener does nothing`: A,
        B, A again gives the order A, B with one call each; removing C changes nothing;
      - `RT-07 a listener whose onEnter returns null stops the scope entry: the operation does not run, earlier restorers run in reverse
        order, then NullPointerException is thrown`: listeners A, B, C, where B returns `null`; the operation and C's `onEnter` are not
        called, A's restorer is, and the scope throws `NullPointerException` (CORE-14). Red before T003: T002 runs the operation;
    - enable RC-9 in `it/RuntimeConformanceIT.java`: two listeners registered for the test, removed in `finally`; for nested bindings and a
      `snapshot.call` that throws, their calls are enter A, enter B, restore B, restore A per scope; a third, throwing listener leaves the
      result unchanged. The listeners record only scopes entered on the test's own thread, because listeners are JVM-wide and the shared
      topology's services enter scopes on other threads (and register their own MDC listeners) while RC-9 runs.
  - Green: per-listener and per-restorer `try`/`catch (RuntimeException)` with `System.Logger` in `ContextRuntime.enter`; `addIfAbsent`; a
    `null` restorer or an `Error` from `onEnter` stops the entry, unwinds the restorers already returned and rethrows; an `Error` from a
    restorer propagates at once. Fix from the review: each restorer is kept with the listener that returned it, and the `WARNING` for a
    restorer's `RuntimeException` names that listener (its class name) instead of the restorer's class.
  - Completes: RT-07; CORE-14 (`null` restorer); RC-9.
  - Verify: Unit `ScopeListenerTest`; RC (all three tasks).
- [x] T004 [P] Capture-site mode in the runtime.
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
      - `RT-08 the mode and sample rate are read from the boundrail.context.diagnostics.capture-sites and sample-rate system properties`:
        with both set (`sampled`, `0.25`), `CaptureSiteProperties.mode()` returns `SAMPLED` and `sampleRate()` returns `0.25`; the previous
        property values are restored in `finally`. Added in T004's review; not a guard (the reading code did not exist before T004; its red
        is shown with a temporary typo in a property-name constant).
  - Green: `ContextRuntime.CaptureSites`, the `volatile` mode pair, `captureSites(...)`, `captureSites()`, `sampleRate()`; `core/internal/
    CaptureSiteProperties.java`, read when `ContextRuntime` initialises.
  - Completes: RT-08; CORE-14 (`captureSites`).
  - Verify: Unit `CaptureSiteModeTest` and `CaptureSitePropertiesTest`.
- [x] T005 Capture sites on snapshots: `on`, `off`, and no walking when off.
  - Red:
    - in `coretest/diagnostics/CaptureSiteTest.java` (package `io.github.boundrail.context.diagnostics`, outside the excluded packages):
      - `DIAG-10 a binding under on records the first frame outside the library, and EMPTY has none`: the site starts with
        `io.github.boundrail.context.diagnostics.CaptureSiteTest.` and is in `StackTraceElement.toString()` form, also when the binding is made
        inside a lambda in the test; `ContextSnapshot.EMPTY.captureSite()` is empty;
      - `DIAG-10 filtering, running and wrapping keep the capture site`: `policy.filter` of a recorded snapshot, and the current snapshot inside
        its `run`, `call` and both `wrap`s, have the same site;
      - `DIAG-05 under off no capture site is recorded`: the same binding then records a site once the mode is `on`, so the off
        assertion sits with a positive one that is red before this task;
    - in `coretest/CaptureSiteRecorderTest.java` (library package, for the seam):
      - `SC-003 with capture sites off no stack walking happens, and with on one walk per binding`: a counting walker replaces the
        recorder's walker for the test; a binding under `off` counts 0, under `on` counts 1.
  - Green: `captureSite()` on `ContextSnapshot`; the field in `ImmutableContextSnapshot`; `core/CaptureSiteRecorder.java`; `ContextBinding.
    applied()` records; `ContextPolicy.filter` keeps ([plan.md](plan.md#core)).
  - Completes: DIAG-10; DIAG-05 (`on`, `off`, core); SC-003.
  - Verify: Unit `CaptureSiteTest` and `CaptureSiteRecorderTest`.
- [x] T006 Sampled capture sites.
  - Red, in `CaptureSiteTest`:
    - `DIAG-05 sampled at rate 1.0 records every snapshot and at 0.0 none`;
    - `DIAG-05 sampled at 0.5 records some but not all of 1000 snapshots` (bounds wide enough never to flake: between 300 and 700).
  - Green: the `SAMPLED` branch with `ThreadLocalRandom`.
  - Completes: DIAG-05 (`sampled`, core).
  - Verify: Unit `CaptureSiteTest`.

**Checkpoint**: core has listeners, the capture-site mode and capture sites; RC-9 is green on all three tasks.

---

## Phase 3: Foundational — violation evidence and listeners (blocks US2)

- [x] T007 Evidence filled by the reporter for every adapter.
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
- [x] T008 Violation listeners.
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

- [x] T009 [P] [US1] `BoundExecutors` for `Executor`.
  - Red, in `coretest/BoundExecutorsTest.java`:
    - `EXE-02 a task runs in the snapshot captured at submission, filtered for the mode`: a key that propagates to `ATTACHED` only is present
      under `attached(...)` and absent under `detached(...)`; a binding changed after submission is not seen;
    - `EXE-04 overloads without a policy filter with BuiltInKeys.defaultPolicy()`: a key an application policy restricts to `ATTACHED` still
      reaches `detached(executor)` (unregistered keys propagate to both), and does not reach `detached(executor, policy)`;
    - `EXE-02 the worker thread is left with no context after the task`;
    - `CORE-14 BoundExecutors rejects null`: each `Executor` overload with a `null` delegate or policy.
  - Green: `core/BoundExecutors.java` with the four `Executor` overloads and a package-private wrapper; Javadoc (TDD exception, reviewed)
    for EXE-04 (default policy), EXE-07 (common-pool `CompletableFuture.*Async` not covered; reactive and Kotlin `suspend` `@Scheduled`
    methods get no system context) and DIAG-07 (the audit sees only the beans DIAG-12 covers; runtime validation covers executors created in
    code).
  - Completes: EXE-02 and EXE-04 (`Executor`); EXE-07; DIAG-07; CORE-14 (`Executor` overloads).
  - Verify: Unit `BoundExecutorsTest`.
- [x] T010 [US1] `BoundExecutors` for `ExecutorService`.
  - Red, in `BoundExecutorsTest`:
    - `EXE-02 submit, invokeAll and invokeAny run each task in the snapshot captured at submission`, for `Runnable` and `Callable`;
    - `EXE-04 lifecycle methods delegate`: `shutdown`, `shutdownNow`, `isShutdown`, `isTerminated`, `awaitTermination`, `close`;
    - `CORE-14 the ExecutorService overloads reject null`, and `invokeAll` with a `null` element.
  - Green: the `ExecutorService` overloads and wrapper.
  - Completes: EXE-02 and EXE-04 (`ExecutorService`); CORE-14 (`ExecutorService` overloads).
  - Verify: Unit `BoundExecutorsTest`.
- [x] T011 [US1] Auto-decoration of Spring executors; enable IT-1.1.
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
- [x] T012 [US1] Composition with an existing decorator, through the private `taskDecorator` field (EXE-10).
  - Red, in `ExecutorDecorationTest`, for each of the three types:
    - `EXE-03 an existing decorator set in code runs inside the library's scope`: the existing decorator records `Context.capture()` when its
      decorated runnable runs, and sees the submitter's binding;
    - `EXE-03 a decorator applied by Boot's builder from TaskDecorator beans is kept and runs inside the library's scope`, also with
      `spring.task.execution.propagate-context=true`, with both, with virtual threads (`spring.threads.virtual.enabled=true`, so Boot builds
      a `SimpleAsyncTaskExecutor`), and for Boot's `taskScheduler`;
    - `EXE-03 Micrometer's ContextPropagatingTaskDecorator still propagates its thread locals`: with a registered `ThreadLocalAccessor`, both
      its value and the context reach the task (`micrometer-context-propagation` as `testImplementation` in `-spring`);
    - `EXE-03 an executor instance post-processed twice is decorated once`: a counting `ScopeListener` sees one scope entry per task, and
      the existing decorator runs once. Not a guard: the existing decorator's single run is red before T012, because T011 replaces it.
      The check that leaves an existing library decorator alone was also shown red by removing it temporarily from the finished code;
  - Red, in `springtest/ReflectedFieldsTest.java`:
    - `EXE-10 the private taskDecorator field of ThreadPoolTaskExecutor, SimpleAsyncTaskExecutor and ThreadPoolTaskScheduler is found by
      name and type`, through the package-private lookup, so a Spring upgrade that renames one fails this test;
    - `EXE-10 a missing field fails startup naming the class and the field, also with lazy initialization`: the post-processor, built
      through a package-private constructor with a field name that does not exist, fails startup for a `ThreadPoolTaskExecutor` bean with an
      `IllegalStateException` whose message names `ThreadPoolTaskExecutor` and the field; and the same with the T006a lazy setup from 003 and
      the executor bean `@Lazy(false)`, so it is processed at startup.
  - Green: the field lookup once per type (`ReflectionUtils.findField`, `IllegalStateException` when missing), `library ∘ existing` set
    through `setTaskDecorator`, and the skip when the existing decorator is the library's ([plan.md](plan.md#spring-wiring)).
  - Completes: EXE-03 (composition, library outermost); EXE-10 (executor fields); SC-002 (with IT-1.1).
  - Verify: Unit `ExecutorDecorationTest` and `ReflectedFieldsTest`; IT (IT-1.1 stays green).
- [x] T013 [US1] Executor modes.
  - Red, in `springtest/ExecutorModeTest.java`:
    - `EXE-01 executors.modes.<bean-name>=attached makes that executor attached, and others stay detached`: an `ATTACHED`-only key reaches
      the attached executor's task and not another's;
    - `EXE-01 modes are matched with relaxed enum binding and a blank value means detached`: `Attached`, `ATTACHED` and ` attached ` are
      attached; `` (through a `MapPropertySource`) is detached;
    - `EXE-01 an invalid mode fails startup, also with lazy initialization`: `bogus`, with and without the T006a lazy setup from 003;
    - `EXE-05 an @Async method on an attached executor runs with the attached context`;
    - `EXE-01 the bean name is matched ignoring case`: `executors.modes.rawexecutor=attached` from a `MapPropertySource`, and the
      environment variable form `BOUNDRAIL_CONTEXT_EXECUTORS_MODES_RAWEXECUTOR=attached` through a `SystemEnvironmentPropertySource` (which
      Boot reads as the lowercase key `rawexecutor`), make the decorated bean `rawExecutor` attached;
    - `EXE-01 executors.modes applies to decorated beans only`: with `attached` set for a decorated bean, a `BoundExecutors.detached` bean and
      a plain `ExecutorService` bean, the decorated bean's task gets the `ATTACHED`-only key, the `BoundExecutors` bean's task does not, and
      the plain bean is still not wrapped;
    - `EXE-01 two modes keys equal ignoring case with different modes fail startup, also with lazy initialization, and the same mode under
      both is accepted`: bracketed keys, because Boot merges unbracketed keys that differ only in case (T001):
      `modes[rawExecutor]=attached` with `modes[RAWEXECUTOR]=detached` (through a `MapPropertySource`) fails with and without the T006a lazy
      setup from 003, naming both keys; `modes[rawExecutor]=attached` with `modes[RAWEXECUTOR]=Attached` starts, and the bean is attached.
    - `EXE-01 one executor instance decorated under two bean names with different modes fails startup naming both beans, and the same mode
      under both is accepted` (D47): one `ThreadPoolTaskExecutor` instance registered under two bean names.
  - Green: `Executors.modes` bound as `Map<String, String>` in `BoundrailContextProperties`; its compact constructor converts each value with
    `ApplicationConversionService.getSharedInstance()` (blank is `DETACHED`) and rejects keys equal ignoring case with different modes; the
    post-processor looks up the bean's mode ignoring case ([plan.md](plan.md#spring-wiring)).
  - Completes: EXE-01; EXE-02 (`ATTACHED`); EXE-05.
  - Verify: Unit `ExecutorModeTest`.

**Checkpoint**: IT-1.1 green on JDK 21 and JDK 25.

---

## Phase 5: User Story 2 - Lost context fails loudly, with evidence (Priority: P1)

**Goal**: Violations name the executor a thread belongs to; the startup audit lists covered executors and gaps, and can fail startup.

**Independent Test**: IT-1.2 – IT-1.5 in the IT command; IT-1.8 is enabled with the test module in T023.

- [x] T014 [US2] Executor evidence; enable IT-1.2 and IT-1.4 (guard).
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
      - `DIAG-04 an executor instance decorated under two bean names is covered under both` (the EXE-01 settlement in d0f8945): one
        `ThreadPoolTaskExecutor` returned by two bean methods with the same mode gives `<name> (COVERED <mode>)` for each name, not `GAP`;
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
- [x] T015 [US2] Startup audit and fail-on-gap; enable IT-1.3 and IT-1.5.
  - Red:
    - in `springtest/ExecutorAuditTest.java` (an `ApplicationReadyEvent` published by the test; the audit logs through
      `LogFactory`, so its entries are read from Boot's captured output with `CapturedOutput`, level and message):
      - `DIAG-06 every executor bean is logged in bean-name order, then the summary`: `Executor audit: aExecutor COVERED (attached)` at INFO,
        `Executor audit: bExecutor GAP` at WARN, `Executor audit: cExecutor COVERED (detached)` at INFO for a `BoundExecutors.detached` bean
        (with `executors.modes.cExecutor=attached` set, which EXE-01 does not apply to it),
        then `Executor audit: 2 covered, 1 gaps` at INFO;
      - `DIAG-06 with auto-decorate=false the Spring executor types are gaps`;
      - `DIAG-12 the audit lists only instantiated singleton executor beans of the context itself, and instantiates none`: a lazy executor
        bean not yet requested, a prototype one and a parent context's one are not listed or counted, and their factory methods were never
        called;
      - `DIAG-04 a BoundExecutors bean is covered in executor evidence`: a thread named after a `BoundExecutors.detached` bean gives
        `<bean> (COVERED detached)`;
      - `DIAG-04 equally long prefixes go to the first bean name in ascending order, and an empty prefix matches nothing`: two pools with the
        same thread-name prefix give the alphabetically first bean; a pool with `setThreadNamePrefix("")` gives no evidence for an unrelated
        thread;
      - `DIAG-12 an executor produced by a FactoryBean is skipped`: a `ThreadPoolExecutorFactoryBean` bean is neither in the evidence nor in
        the audit;
      - `DIAG-06 a modes key that matches no decorated executor is logged at WARN`: `executors.modes.rawExecuter=attached` with no such
        bean gives `Executor audit: executors.modes.rawExecuter matches no decorated executor`; a key matching a decorated bean gives none;
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
- [x] T015a [US2] Unmatched mode keys by bean definition, and strict audit flags (T015 review follow-up).
  - Red, in `springtest/ExecutorAuditTest.java`:
    - `DIAG-06 a modes key naming a lazy or prototype executor bean definition is not reported as unmatched`: `executors.modes.lazyPool`
      for a `@Lazy` `SimpleAsyncTaskExecutor` not yet requested and `executors.modes.jobPool` for a prototype `ThreadPoolTaskExecutor` give
      no unmatched-key WARN, and neither factory method is called; a key naming no definition still warns. (A `@Lazy`
      `ThreadPoolTaskExecutor` or `ThreadPoolTaskScheduler` is a `SmartLifecycle` bean that Spring creates at refresh, so it is not a lazy
      case.);
    - `DIAG-06 the audit flags are bound as Boot booleans`: `audit.enabled=yes` turns the audit on (red before T015a, whose
      `@ConditionalOnProperty` matches only `true`); `audit.enabled=FALSE` turns it off; `audit.enabled=maybe` and `audit.fail-on-gap=maybe`
      fail startup, with and without the T006a lazy setup.
  - Green: the unmatched-key check reads the context's bean definitions of the three EXE-03 types
    (`getBeanNamesForType(type, true, false)`), and the audit is enabled through the bound `Audit` record instead of
    `@ConditionalOnProperty`.
  - Completes: DIAG-06 (unmatched keys by definition, audit flag binding).
  - Verify: Unit `ExecutorAuditTest`.
- [x] T016 [P] [US2] Capture-site properties.
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

- [x] T017 [US3] MDC bridge; enable IT-1.6.
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
- [x] T018 [US3] MDC across nested scopes and several contexts.
  - Red, in `MdcScopeListenerTest`:
    - `LOG-01 a nested Context.without(tenant) removes tenant from the MDC and restores it on exit`;
    - `LOG-01 each application context's listener writes only its own policy's loggable keys`: two contexts, each with one loggable key;
    - `LOG-01 the listener is removed when its context closes`: after `close()`, a scope writes nothing for that context's keys;
    - `LOG-01 a codec that throws while entering leaves the MDC as it was` (T017 review): with two loggable keys whose second codec throws a
      `RuntimeException`, the first key's MDC entry is put back to its previous value, and the scope's outcome is as RT-07 says.
    - `LOG-01 a codec that throws an Error while entering leaves the MDC as it was, and the Error propagates` (T018 review): the same with an
      `Error`; the operation does not run and the `Error` propagates unchanged (RT-07). Loggable keys are walked in name order, so both
      codec tests are deterministic.
  - Green: removal of unbound names on entry; `DisposableBean.destroy()` removes the listener; `onEnter` restores the names it already wrote
    before rethrowing.
  - Completes: LOG-01 (nested scopes, several contexts, removal; D44).
  - Verify: Unit `MdcScopeListenerTest`.

**Checkpoint**: IT-1.6 green on JDK 21 and JDK 25.

---

## Phase 7: User Story 4 - Scheduled jobs get a system context (Priority: P3)

- [x] T019 [US4] `@Scheduled` system context; enable IT-1.7.
  - Red:
    - in `springtest/ScheduledContextTest.java` (`ApplicationContextRunner` with `@EnableScheduling` and a fast `fixedDelay`):
      - `EXE-06 a @Scheduled method invoked by the scheduler runs with exactly initiator=system:scheduled:<beanName>.<methodName>, and a
        direct call keeps the caller's context`: bean `cleanupJob`, method `run`; the test then calls `cleanupJob.run()` inside a binding of
        `tenant`, which the method sees, with no initiator;
      - `EXE-06 a @Scheduled method never sees context left over from another task`: the scheduler is a one-thread `ThreadPoolTaskScheduler`
        whose decorator captured a binding at scheduling (the task scheduled from inside a binding), and the method still sees only the
        initiator;
      - `DIAG-05 under on the @Scheduled binding records a capture site in the library's scheduled support`;
      - `EXE-06 only Spring's own scheduled-annotation post-processor definition is replaced, and any other is left with one startup WARN`:
        with `@EnableScheduling` the definition named `org.springframework.context.annotation.internalScheduledAnnotationProcessor` is the
        library's subclass and no WARN is logged; with an application's own definition under that name (and no `@EnableScheduling`), it is
        left as registered, one WARN naming it is logged (`RecordingLog` or `OutputCaptureExtension`), and its scheduled method runs without
        the system context; with neither, nothing is registered or logged;
      - `EXE-06 the scheduled task keeps its description`: the `ScheduledTask` of `cleanupJob.run`, read through `ScheduledTaskHolder`, has a
        runnable whose `toString()` names the target method, as Spring's own does;
    - enable IT-1.7: edge's `cleanupJob.run` records exactly `initiator=system:scheduled:cleanupJob.run` to `ObservedContext` (bounded poll).
  - Green: `spring/ScheduledContextRegistrar.java` (a `static` `BeanDefinitionRegistryPostProcessor`) and
    `spring/ContextScheduledAnnotationBeanPostProcessor.java` with its `ScheduledMethodRunnable` subclass ([plan.md](plan.md#spring-wiring));
    topology test code: `@EnableScheduling` and `topology/CleanupJob.java` (Boot's scheduler threads are `scheduling-N`).
  - Completes: EXE-06; IT-1.7.
  - Verify: Unit `ScheduledContextTest`; IT.

---

## Phase 8: User Story 5 - Spring MVC async requests keep the request's context (Priority: P2)

- [x] T020 [US5] `ASYNC` dispatches re-enter the request's snapshot.
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
- [x] T021 [US5] Handler `Callable`s run in the request's snapshot, filtered for `ATTACHED`; enable IT-1.9.
  - Red:
    - in `springtest/MvcCallableTest.java` (`MockMvc`):
      - `EXE-09 a Callable returned by a handler runs in the request's snapshot filtered for attached`: an `ATTACHED`-only key is present and a
        key that does not propagate to `ATTACHED` is absent;
      - `EXE-09 this holds whatever the mode of MVC's executor`: MVC's executor is a library-decorated `ThreadPoolTaskExecutor` in `detached`
        mode;
      - `EXE-09 this holds with MVC's internal default executor`, when no executor is configured;
      - `EXE-09 a WebAsyncTask without its own executor runs in the same snapshot, and its onCompletion and timeout callbacks still fire`:
        the timeout is fired through the `MockAsyncContext` listeners, and the result read with
        `WebAsyncUtils.getAsyncManager(request).getConcurrentResult()` (T001);
    - in `springtest/ReflectedFieldsTest.java`:
      - `EXE-10 the private taskExecutor field of RequestMappingHandlerAdapter is found by name and type`;
      - `EXE-10 a missing taskExecutor field fails startup naming the class and the field, also with lazy initialization`: the MVC
        post-processor, built with a field name that does not exist, fails startup for a `RequestMappingHandlerAdapter` bean, and the same
        with the T006a lazy setup and the adapter bean `@Lazy(false)`;
    - enable IT-1.9: alice's request to edge's `Callable` endpoint reaches orders with `tenant=acme`; for the `DeferredResult` endpoint, the
      edge `HandlerInterceptor` records `tenant=acme` in `postHandle` of the async dispatch.
  - Green: `spring/MvcAsyncExecutorPostProcessor.java` and `spring/AttachedAsyncTaskExecutor.java` in a nested
    `@ConditionalOnClass(RequestMappingHandlerAdapter.class)` configuration ([plan.md](plan.md#spring-wiring)); `spring-webmvc` as
    `compileOnly`; topology test code: the two endpoints and the interceptor.
  - Completes: EXE-09; EXE-10 (MVC field); IT-1.9 (with IN-09).
  - Verify: Unit `MvcCallableTest` and `ReflectedFieldsTest`; IT.

**Checkpoint**: IT-1.7 and IT-1.9 green on JDK 21 and JDK 25.

---

## Phase 9: Test support (US2's IT-1.8)

- [x] T021a [US5] Check the MVC field at startup (T021 review follow-up).
  - Red, in `springtest/ReflectedFieldsTest.java`: `EXE-10 a missing taskExecutor field fails startup even when the adapter is created
    lazily`: with the renamed-field post-processor and a `RequestMappingHandlerAdapter` bean that is lazy and never requested, startup still
    fails naming the class and the field (red: T021 looks the field up only when an adapter is post-processed).
  - Green: `MvcAsyncExecutorPostProcessor` looks the field up in its constructor.
  - Completes: EXE-10 (MVC field checked at startup).
  - Verify: Unit `ReflectedFieldsTest`.
- [x] T022 [P] `SnapshotAssert` and the test module's API dependencies.
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
- [x] T023 `@BoundrailTest` and the violation recorder; enable IT-1.8.
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
  - Green: `testmod/BoundrailTest.java` (its Javadoc says parallel test methods are not supported: approved TDD exception),
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
  - Requirements: CORE-14, RT-07 – RT-09, IN-09, EXE-01 – EXE-07, EXE-09, EXE-10, DIAG-04 – DIAG-07, DIAG-10 – DIAG-12, LOG-01, LOG-02 and
    TEST-02 – TEST-04.
  - SC-001: RuntimeConformanceIT and ContextPropagationIT have no `@Disabled` left; RC-9 passes in all three tasks, IT-1.1 – IT-1.9 in
    `integrationTestJdk21` and `integrationTestJdk25`.
  - SC-002: IT-1.1 runs with Micrometer's decorator configured on `taskExecutor`, and T012's test shows it still works.
  - SC-003: T005's walker test; by inspection, the `OFF` path in `CaptureSiteRecorder` returns before any `StackWalker` call.
  - By inspection (EXE-10): reflection is used only on the four fields EXE-10 lists, each read once per bean and written only through the
    public setters.
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

- T001 is done; nothing waits on it any more.
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
  EXE-01 – EXE-07, EXE-09 – EXE-10, DIAG-04 – DIAG-07, DIAG-10 – DIAG-12, LOG-01 – LOG-02, TEST-02 – TEST-04; the Javadoc
  statements of EXE-04, EXE-07, DIAG-07 and TEST-02 (parallel runs) checked by inspection (TDD exceptions)
- [ ] Guard tests (IT-1.4, and the unit guards in T003, T008, T011, T015, T016, T017, T020 (two) and T023 (two)) shown red against their
  temporary change, and the change reverted
- [x] T001's outcome recorded in plan.md, and G1 settled by the user (EXE-10, D47)
- [ ] `boundrail-context-core` still has no runtime dependencies; SLF4J and Spring MVC are `compileOnly` in `-spring`
- [ ] `./gradlew check` green
