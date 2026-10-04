# Implementation Plan: Async Work and Diagnostics

**Branch**: `004-async-diagnostics` | **Date**: 2026-10-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-async-diagnostics/spec.md`, contracts
[contracts/runtime-listeners.md](contracts/runtime-listeners.md) and [contracts/executors-and-test.md](contracts/executors-and-test.md),
decisions D39 – D47 in [design.md](../../docs/design.md), the T001 spike's outcome

**Status**: Approved by the user (plan, tasks, both TDD exception rows and all eleven guard tests). The spike T001 is done, and its outcome is
recorded below. Every spec gap is closed: G2 – G14 in spec.md and design.md D46 (DIAG-12 is new), G1 by the user's decision to allow
reflection, written into spec.md as EXE-10 with amended EXE-06 and EXE-07 (D47). All eleven guard tests are accepted.

## Summary

Context survives in-process async boundaries, and when it does not, the failure is loud and states only what was observed.

- `boundrail-context-core`:
  - the `ScopeListener` hook, called around every scope entry on both backends (RT-07);
  - the JVM-wide capture-site mode, from system properties or `ContextRuntime.captureSites` (RT-08);
  - `ContextSnapshot.captureSite()`, recorded with `StackWalker` by bindings when capture sites are `on` or `sampled` (DIAG-05, DIAG-10);
  - `BoundExecutors`, which capture at submission and run tasks in the filtered snapshot (EXE-02, EXE-04, EXE-07, DIAG-07).
- `boundrail-context-spring`, in the existing auto-configuration:
  - the reporter fills every violation's thread, capture site and executor evidence, and passes it to `ViolationListener` beans (DIAG-04,
    DIAG-11);
  - a bean post-processor installs the library's `TaskDecorator` on Spring's executor and scheduler types, composed with an existing decorator,
    with a mode per bean (EXE-01 – EXE-03, EXE-05);
  - the executor table for evidence, and the startup audit with `fail-on-gap` (DIAG-04, DIAG-06);
  - the capture-site properties, applied only when set (DIAG-05);
  - the MDC bridge, one `ScopeListener` per application context (LOG-01, LOG-02);
  - the `@Scheduled` system context (EXE-06);
  - Spring MVC async processing: the request's snapshot re-entered on `ASYNC` dispatches, and a handler's `Callable` run in it, filtered
    for `ATTACHED` (IN-09, EXE-09).
- `boundrail-context-test`: `@BoundrailTest`, the JVM-wide violation recorder auto-configured as a `ViolationListener`, `RecordedViolations`,
  and `ContextAssertions`; JUnit Jupiter API and AssertJ as `api` dependencies (TEST-02 – TEST-04, D40).

Library code is driven by unit and `ApplicationContextRunner` tests. Each ContextPropagationIT scenario is enabled in the task whose code
makes it pass, so it is that task's failing acceptance test. RC-9 is enabled in T003. One scenario (IT-1.4) and ten unit tests already pass
before their task: the nine first approved and the one added in T003, all accepted. The T012 test accepted as a guard turned out red before
T012 (T011 replaces the existing decorator), so it is an ordinary test. Each red is shown against a named temporary
change that is then reverted ([Guard Tests](#guard-tests)). The T003 guard the user accepted for the `null` restorer is no longer one: with
G13 settled, its test also asserts that the operation does not run, which is red before T003.

## Technical Context

**Language/Version**: Java 21 (`--release 21`) everywhere; nothing new in core's `java25` source set (the listener hook sits above the slot,
so both backends share it)

**Primary Dependencies**:

- core: none (BLD-03).
- `-spring`: Spring Boot 4.1.1 and Spring Framework 7.0.9. New `compileOnly` and `testImplementation` entries, each added in the green step of
  the first task that needs it: `slf4j-api` (T017), `spring-webmvc` (T020). `spring-context` (executors, `@Async`, `@Scheduled`) and
  `spring-core` (`TaskDecorator`, `CompositeTaskDecorator`) already arrive through `spring-boot-autoconfigure`.
- `-test`: `api` on `junit-jupiter-api` and `assertj-core` (TEST-04, T022); `compileOnly` on `spring-boot-autoconfigure` for the recorder's
  auto-configuration (T023).
- `integration-tests`: `io.micrometer:context-propagation` (T011), so that Boot's `spring.task.execution.propagate-context=true` registers
  Micrometer's `ContextPropagatingTaskDecorator` (Boot 4.1.1 `TaskExecutorContextPropagationConfiguration`). It is not in the local Gradle
  cache, so T011's first build needs network access.

**Storage**: N/A

**Testing**:

- core unit tests; capture-site location tests in a test package outside the library's packages (see [Core](#core));
- `-spring` tests with `ApplicationContextRunner`, `WebApplicationContextRunner`, `MockMvc` with `asyncDispatch` (spring-test), and
  `support/RecordingLog`;
- `-test` unit tests, which run JUnit test classes through the JUnit Platform `EngineTestKit` where an extension's lifecycle is asserted;
- RuntimeConformanceIT RC-9 on all three integration test tasks; ContextPropagationIT on `integrationTestJdk21` and `integrationTestJdk25`;
  BuildSmokeIT for TEST-01 and TEST-04.

**Target Platform**: JVM 21+, servlet-based Spring MVC (no WebFlux, no Reactor, no default-pool `CompletableFuture`; spec Assumptions)

**Project Type**: library (Spring Boot auto-configuration)

**Performance Goals**: with no listener registered and capture sites `off`, a scope entry does no extra work beyond one empty-list check and
one volatile read (SC-003); no reflection per task or per request (EXE-10: four private fields, each read once per bean at startup)

**Constraints**:

- JVM-wide state only as RT-09 lists, plus the test module's recorder (D40).
- Every adapter stays around-style: no set/restore hooks such as `CallableProcessingInterceptor.preProcess`/`postProcess` (principle I,
  ScopedValue backend).
- MDC entries restored, never cleared (`boundrail-conventions`).
- No work from M4 – M6: no deadline enforcement in executors (EXE-08 is M4), no authority, no Kafka.

**Scale/Scope**: 5 contract types plus additions to `ContextRuntime` and `ContextSnapshot`; 27 requirements; RC-9 and 9 scenarios; a spike, 23
red-green tasks (T002 – T024) and a close task (T025)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|---|---|---|
| I Scope-only | Pass | Decorators, `BoundExecutors`, the `@Scheduled` support and the `ASYNC` dispatch all run work inside `ContextSnapshot.call`/`ContextBinding.call`; the MDC bridge restores previous values, never clears |
| II Declared propagation | Pass | Executors filter with the policy for `ATTACHED` or `DETACHED` (POL-04); nothing new crosses a remote boundary |
| III Secure by default | Pass | `AUTHORITY` never reaches the MDC (LOG-02); an `ASYNC` dispatch re-enters the stored snapshot without trusting any header again (IN-09) |
| IV Declared, never guessed | Pass | Mode per executor bean, default `DETACHED` (EXE-01); `Callable` work is `ATTACHED` by declaration (EXE-09, D39) |
| V Evidence only | Pass | Violations carry thread, capture site and executor evidence only as observed (DIAG-04); stack walking only when `on` or `sampled` (SC-003) |
| VI Interoperate | Pass | EXE-03 composes with Micrometer's, Boot's and code-set decorators, library outermost, by reading Spring's private `taskDecorator` field once per bean (EXE-10, D47) |
| VII Spec-first, test-first | Pass | Every task is one red-green cycle; the eleven guard tests and both Javadoc TDD exception rows are approved |
| VIII Simplicity | Pass | One post-processor, one decorator, one table shared by evidence and audit, one listener per context; no public audit API (D42) |
| Technical constraints | Pass | Core stays dependency-free (T025); SLF4J and Spring MVC `compileOnly` behind `@ConditionalOnClass` |

## Spike (T001)

Done; no code kept. Verified on Spring Boot 4.1.1 / Framework 7.0.9, on JDK 21 and JDK 25. The user chose option A for EXE-03 and EXE-09 and
option B for EXE-06 (G1 closed); spec.md has them as EXE-10 and amended EXE-06 and EXE-07 (D47).

1. **EXE-03: reflection (option A).** Reading the private `taskDecorator` field of `ThreadPoolTaskExecutor`, `SimpleAsyncTaskExecutor` and
   `ThreadPoolTaskScheduler` once per bean, before initialisation, then `setTaskDecorator(library ∘ existing)`, keeps the existing decorator
   running inside the library's scope. Verified for Boot's executor with a `TaskDecorator` bean, with `spring.task.execution.propagate-context
   =true` (Micrometer), with both, with virtual threads, for Boot's `taskScheduler`, and for decorators set in code. A missing field fails
   startup (user decision).
2. **EXE-09: wrap MVC's executor (option A).** Reading `RequestMappingHandlerAdapter`'s private `taskExecutor` once in
   `postProcessAfterInitialization` and calling `setTaskExecutor(wrapper)`: a `Callable` and a `WebAsyncTask` with no executor of its own get
   the request's snapshot, their `onCompletion` and timeout callbacks still fire, a `WebAsyncTask` with its own executor bypasses the wrapper,
   and MVC's internal default executor is covered too. A missing field fails startup. MockMvc caveat: fire timeouts through the
   `MockAsyncContext` listeners, and read the result with `WebAsyncUtils.getAsyncManager(request).getConcurrentResult()`.
3. **EXE-06: subclass the scheduled-annotation post-processor (option B).** A `BeanDefinitionRegistryPostProcessor` removes Spring's own
   `org.springframework.context.annotation.internalScheduledAnnotationProcessor` definition (a factory method on `SchedulingConfiguration`) and
   re-registers it with a subclass. Correction to the first draft: the third argument of `createRunnable(Object, Method, String)` is the
   scheduler qualifier, not the bean name, so the subclass keeps the bean name in an instance `ThreadLocal` set around
   `super.postProcessAfterInitialization(bean, beanName)`. It returns a `ScheduledMethodRunnable` subclass, so the task's description is kept.
   If the definition is not Spring's own, the library leaves it and logs a WARN at startup (user decision). Reactive and Kotlin `suspend`
   `@Scheduled` methods are excluded, and the `BoundExecutors` Javadoc says so (user decision). Boot's scheduler threads are `scheduling-N`,
   its executor threads `task-N`.
4. **EXE-01: map keys.** Properties-file keys keep their case; environment-variable keys are lowercase, with `_` read as `.`. Boot drops blank
   values from a `Map<String, Mode>`, and merges keys that differ only in case unless they are bracketed. So the modes are bound as
   `Map<String, String>` and converted with `ApplicationConversionService.getSharedInstance()` (lenient; `bogus` gives
   `ConversionFailedException`), which keeps blank-means-detached and the conflict check of G14 possible; the conflict test uses bracketed keys
   (`modes[rawExecutor]=attached` with `modes[RAWEXECUTOR]=detached`).
5. **Dependency.** `io.micrometer:context-propagation`, managed by the Boot BOM.

## Research Notes

### Build

- **Dependencies go in the green step of the first task whose failing test needs them**, as in 002 and 003, with catalog entries managed by the
  Boot BOM: `micrometer-context-propagation` (T011, `integration-tests`), `slf4j-api` (T017), `spring-webmvc` (T020), `junit-jupiter-api`
  (T022). So none is a TDD exception.
- **TEST-04 has a failing check.** T022 extends BuildSmokeIT: `junit-jupiter-api` and `assertj-core` appear with scope `compile` in the
  generated POM and under the `apiElements` variant in `module.json` of `boundrail-context-test`. The BOM is a `compileOnly` platform in `-test`,
  so the `api` dependencies may be written without a version. If `generatePomFileForMavenPublication` then fails or warns, T022 adds
  `versionMapping { allVariants { fromResolutionResult() } }` to the publication in the same green step; nothing is published (Q5 stays open).
- **The test module gains an imports file (TEST-02).** `boundrail-context-test` now auto-configures the recorder, so two existing TEST-01 tests
  contradict the new behaviour and are rewritten in T023: BuildSmokeIT's `TEST-01 the boundrail-context-test JAR ships no
  AutoConfiguration.imports and no spring.factories` and `TestInboundTrustVerifierTest`'s `TEST-01 the verifier is not auto-configured`
  (whose scan rejects any imports entry in the test package). TEST-01 itself only forbids auto-configuring the verifier and `peer()`, so the
  rewritten tests assert that the JAR's imports file lists exactly the recorder's auto-configuration and that it registers no
  `InboundTrustVerifier` bean. The user is asked to accept the rewrite with the plan.

### Core

- **One entry point for scopes (RT-07).** `ContextBinding.run`/`call` and `ImmutableContextSnapshot.run`/`call` (and so `wrap`) go through one
  package-private `ContextRuntime.enter(snapshot, op)`, which calls `SLOT.call(snapshot, …)` and, inside it, after the snapshot is current:
  1. if no listener is registered, calls `op` (fast path);
  2. else calls each listener's `onEnter(snapshot)` in registration order, keeping the returned restorers;
  3. calls `op` in `try`, and in `finally` calls the restorers in reverse order, before `SLOT.call` restores the previous snapshot.

  Listeners are held in a `CopyOnWriteArrayList`, so a scope sees the list as it was at entry; `addIfAbsent` keeps a listener added twice
  once, in its original position, and `remove` of an unregistered one does nothing (RT-07, D46). A listener whose `onEnter` throws a
  `RuntimeException` has no restorer; that exception, and any `RuntimeException` from a restorer, is logged at `WARNING` through
  `System.getLogger("io.github.boundrail.context")` and the next listener or restorer runs; the record for a restorer names the listener that
  returned it (its class name), so each restorer is kept with its listener. An `Error` is not caught: from an `onEnter`, it stops the entry
  (no later listener, no operation), the restorers already returned run in reverse order, and it propagates; from a restorer, it propagates at
  once and the remaining restorers are skipped (RT-07, settled during T003's review). The operation's result
  or exception is returned or rethrown unchanged. Tests capture the log by attaching a `java.util.logging.Handler` to that logger name (the
  default `System.LoggerFinder` maps to JUL), so no test seam is needed.
- **A `null` restorer (RT-07, CORE-14).** When `onEnter` returns `null`, `enter` does not call the operation or the remaining listeners, calls
  the restorers already returned by earlier listeners in reverse order, and then throws `NullPointerException`.
- **Capture-site mode (RT-08).** One immutable `(mode, sampleRate)` pair in a `volatile` field of `ContextRuntime`, so the two always change
  together. Initialised when `ContextRuntime` is first used, from a package-private parser in `internal` that takes the property values and a
  `System.Logger`, like `BackendOverride`. Values compare exactly, as RT-04 does (so `ON` warns). The rate is parsed with
  `Double.parseDouble`, so any syntax it accepts (`" 0.5 "`, `5e-1`) is valid when the result is within 0.0 – 1.0; NaN is rejected (RT-08).
  Defaults: `OFF` and `0.01`.
- **Capture sites (DIAG-10, DIAG-05).** `ImmutableContextSnapshot` gains a nullable `captureSite` field. Only `ContextBinding.applied()`
  records one, through a package-private recorder that:
  1. reads the mode once; `OFF` returns empty with no `StackWalker` call (SC-003);
  2. for `SAMPLED`, records when `ThreadLocalRandom.current().nextDouble() < sampleRate`;
  3. walks with `StackWalker.getInstance()` and returns `toStackTraceElement().toString()` of the first frame whose class's package (the class
     name up to its last `.`, nested and lambda classes included) is neither `io.github.boundrail.context` nor
     `io.github.boundrail.context.internal`.

  `ContextPolicy.filter` passes the filtered snapshot's site on; `run`, `call` and `wrap` create no snapshot, so they keep it. `EMPTY` has none.
  The walk itself goes through a package-private `Supplier<String>` field that a core test in the library's package replaces with a counting
  one, the only way to observe "no stack walking" (SC-003).
- **Capture-site tests live outside the library's packages.** Core's tests are in `io.github.boundrail.context`, whose frames DIAG-10 skips. So
  the location tests are in `coretest/diagnostics/CaptureSiteTest.java`, package `io.github.boundrail.context.diagnostics`, which is not
  excluded (only the two exact packages are), and assert a site in that test class.
- **BoundExecutors (EXE-02, EXE-04).** Package-private wrappers in core. `execute`, `submit`, `invokeAll` and `invokeAny` capture
  `Context.capture()` on the submitting thread, filter it with the policy for `ATTACHED` or `DETACHED`, and pass `filtered.wrap(task)` to the
  delegate. Lifecycle methods delegate. Overloads without a policy use `BuiltInKeys.defaultPolicy()` (D43). The audit needs to recognise the
  wrappers and their mode from `-spring`, so T015 adds a small public interface in `io.github.boundrail.context.internal` that they implement
  (`Boundary mode()`), as `internal.ContextSlot` is public today. It is not part of the contract.

### Spring wiring

- **Violation evidence (DIAG-04).** `ViolationReporter` gains `Violation violation(Kind, String key, Boundary, Optional<Destination>)`, which
  fills `thread` from the current thread, `captureSite` from `Context.capture().captureSite()`, and `executorEvidence` from the executor table
  (from T014). Every adapter builds its violation through it (inbound filter, authentication filter's `invalid`, client interceptor), so the
  `ContextViolationException` thrown in `fail` mode carries the same evidence. `report(Violation)` and its tests stay. The WARN entry appends
  `, capture site <site>` and `, executor <evidence>` after `, thread <name>` when present (D42).
- **Violation listeners (DIAG-11).** `report` counts, logs if due, then calls each `ViolationListener` from `ObjectProvider.orderedStream()`,
  each in its own `try`; an exception is logged at WARN through the reporter's `Log`, with the exception and the listener's class name
  (DIAG-11), and the next listener runs. Nothing is rethrown, so the adapter's outcome is unchanged.
- **Decoration (EXE-02, EXE-03).** A `static` `@Bean` `BeanPostProcessor`, so the configuration class is not created early. It takes
  `ObjectProvider<EffectivePolicy>` and the `Environment`, and resolves both on first use, so no application `ContextPolicy` bean is created
  during post-processor registration. In `postProcessBeforeInitialization` of a `ThreadPoolTaskExecutor`, `SimpleAsyncTaskExecutor` or
  `ThreadPoolTaskScheduler` (and only those, EXE-04), unless `executors.auto-decorate=false`, it reads the bean's private `taskDecorator`
  field and sets `library ∘ existing` for the bean's mode (T001). The field is looked up once per type with `ReflectionUtils.findField(type,
  "taskDecorator", TaskDecorator.class)`; if it is missing, the post-processor throws `IllegalStateException` naming the type and field, which
  fails startup. If the existing decorator is already the library's (the same instance post-processed twice), it is left as it is, so the
  library never wraps itself. The decorator runs on the submitting thread (Spring calls `decorate` in `execute`/`submit`), captures,
  filters with the effective policy and returns `filtered.wrap(task)` around the existing decorator's result. It records each decorated bean
  name and mode for the table (T014). A periodic task on the scheduler is decorated once, at scheduling, so every run re-enters the snapshot
  captured then; EXE-06 replaces it for `@Scheduled` methods.
- **Modes (EXE-01).** `BoundrailContextProperties` gains `Executors(@DefaultValue("true") boolean autoDecorate, @DefaultValue Map<String,
  String> modes)`, with `Mode { ATTACHED, DETACHED }`. Boot drops blank values from a map of enums, so the raw strings are bound and the
  compact constructor converts each with `ApplicationConversionService.getSharedInstance()` (Boot's lenient enum conversion, so relaxed
  matching); a blank value is `DETACHED`, and an invalid one throws `ConversionFailedException`, which fails binding (T001). The `@Lazy(false)`
  reporter already binds the properties at startup, so the failure also occurs under lazy initialization (T013's lazy test uses the
  T006a setup from 003). The post-processor looks the bean name up among the map's keys ignoring case (`equalsIgnoreCase`), because
  relaxed binding lowercases keys from environment variables. Modes apply to decorated beans only:
  a `BoundExecutors` wrapper keeps the mode of the method that created it, and a plain executor has none (EXE-01, D46). The `Executors`
  compact constructor rejects two keys equal ignoring case with different modes (a blank value counts as `DETACHED`) with
  `IllegalArgumentException` naming both keys; the same mode under both is accepted. It runs when the `@Lazy(false)` reporter binds the
  properties, so the failure also occurs under lazy initialization, and it does not depend on a bean of that name existing, since only bean
  names can match two keys that are equal ignoring case.
- **Executor table (DIAG-04) and audit (DIAG-06).** One package-private `ExecutorTable` bean, filled on `ContextRefreshedEvent` (fired by
  `SpringApplication` and by `ApplicationContextRunner`; DIAG-04's "once the application context has started"), whether or not the audit is
  enabled. It covers only what DIAG-12 allows: `getBeanNamesForType(Executor.class, false, false)` on the context's own bean factory (no
  ancestors, no prototypes, no eager initialisation of factory beans), keeping only names whose singleton already exists
  (`containsSingleton`), and reading each instance with `getSingleton`, so nothing is instantiated. It classifies each as `COVERED <mode>` when
  decorated by the
  post-processor or a `BoundExecutors` wrapper, else `GAP`. Prefixes: the bean name for every executor bean, plus `getThreadNamePrefix()` for
  the EXE-03 types (Spring defaults it to `<beanName>-`). Lookup picks the longest prefix the thread name starts with. Before the event the
  table is empty, so there is no evidence. The audit is an `ApplicationReadyEvent` listener, conditional on `audit.enabled` (default `true`),
  that logs the table's entries in bean-name order and the summary in D42's fixed formats, then, with `audit.fail-on-gap=true` and a gap,
  throws `IllegalStateException` listing every gap; `SpringApplication.run` turns that into a startup failure. Runner tests publish an
  `ApplicationReadyEvent` themselves.
- **Capture-site properties (DIAG-05).** A `@Lazy(false)` bean reads the raw values from the `Environment`, so it can tell an unset property
  (left unchanged) from a blank one: a blank `capture-sites` means `off`, and a blank `sample-rate` counts as not set (DIAG-05). It matches
  `capture-sites` with
  Boot's lenient enum conversion, then calls `ContextRuntime.captureSites(mode, rate)` with the current value of whichever is unset. An
  invalid value throws during startup, also under lazy initialization. Tests restore the JVM-wide mode in `@AfterEach`.
- **MDC bridge (LOG-01, LOG-02).** In a nested `@ConditionalOnClass(org.slf4j.MDC)` configuration, a `@Lazy(false)` bean that registers a
  `ScopeListener` in its constructor and removes it in `destroy()` (`DisposableBean`, called when the context closes). It precomputes the
  effective policy's loggable keys, minus `AUTHORITY` and `DEADLINE` (built-in rules make neither loggable, and POL-06 forbids re-registering
  them, so LOG-02 is also enforced by this exclusion). On entry, for each name: remember `MDC.get`, then `put` the encoded value (codec, or
  `toString()`) if bound in the entered snapshot, else `remove`. The restorer puts back each remembered value or removes the name.
- **`@Scheduled` (EXE-06).** A `static` `BeanDefinitionRegistryPostProcessor` checks the definition named
  `org.springframework.context.annotation.internalScheduledAnnotationProcessor`. When it is Spring's own (factory bean `SchedulingConfiguration`,
  factory method `scheduledAnnotationProcessor`), it removes it and registers a `RootBeanDefinition` of the package-private subclass
  `ContextScheduledAnnotationBeanPostProcessor` under the same name, with `ROLE_INFRASTRUCTURE`. When it is anything else, it leaves it and logs
  a WARN naming the bean; with no such definition (no `@EnableScheduling`) it does nothing. The subclass:
  - sets an instance `ThreadLocal<String>` to the bean name around `super.postProcessAfterInitialization(bean, beanName)`, cleared in `finally`
    (startup bookkeeping in one context's bean, not context state, so RT-09 holds);
  - overrides `createRunnable(Object target, Method method, String qualifier)` to return a `ScheduledMethodRunnable` subclass with the same
    target, method and qualifier, whose `run()` is `ContextSnapshot.EMPTY.call(() -> Context.with(INITIATOR, Initiator.of("system:scheduled:"
    + beanName + "." + method.getName())).call(...))` around `super.run()`. That is a binding, so its capture site is recorded under `on` and
    names the library's frame (DIAG-05, D41).

  Direct calls by application code do not go through the runnable, so they keep the caller's context. Reactive and Kotlin `suspend` methods
  are scheduled by Spring through another path, and are excluded (Javadoc of `BoundExecutors`).
- **Spring MVC async (IN-09, EXE-09).**
  - The inbound filter's registration sets `DispatcherType.REQUEST` and `ASYNC` explicitly (today Boot registers a `OncePerRequestFilter` for
    every type). The filter overrides `shouldNotFilterAsyncDispatch()` to `false`.
  - On the initial dispatch it stores `Context.capture()`, taken inside its own scope or the caller's, in a request attribute named after the
    filter class; the authentication filter overwrites it inside its own scope when it enters one, so the attribute holds the innermost.
  - On an `ASYNC` dispatch (`isAsyncDispatch(request)`) it runs the rest of the chain inside the stored snapshot with `snapshot.call`, without
    calling the verifier, decoding or reporting; with no stored snapshot, in the current scope. The authentication filter keeps
    `OncePerRequestFilter`'s default, which skips async dispatches.
  - In a nested `@ConditionalOnClass(RequestMappingHandlerAdapter.class)` configuration, a `static` `BeanPostProcessor`, in
    `postProcessAfterInitialization` of each `RequestMappingHandlerAdapter`, reads its private `taskExecutor` field once (a missing field fails
    startup with `IllegalStateException`) and calls `setTaskExecutor` with a package-private `AsyncTaskExecutor` wrapper. The wrapper's
    `submit`/`execute` capture `Context.capture()` on the request thread, filter it with the effective policy for `ATTACHED`, and pass
    `filtered.wrap(task)` to the original executor; since the wrap is inside that executor's own decorator, the task sees the `ATTACHED`
    snapshot whatever the executor's mode. A `WebAsyncTask` with its own executor bypasses it, as EXE-09 allows (T001).
- **RT-09.** Nothing new in `-spring` is static. The executor table, recorder registration, MDC listener and capture-site application are beans
  of one context. T008 pins that the reporter's rate limiter is per context; T025 checks by inspection that core has no other static mutable
  state than the slot, the listener list and the capture-site pair.

### Test module

- `BoundrailTestExtension` implements `BeforeAllCallback`, `AfterAllCallback`, `BeforeEachCallback`, `AfterEachCallback` and
  `ParameterResolver`. Before all: save `captureSites()` and `sampleRate()` in the class's store, then set `ON` with the saved rate. After all:
  restore both. Before each: start a fresh recording. After each: stop it.
- The recorder is a static, thread-safe holder in `-test` (D40). An auto-configuration in `-test`, listed in its own imports file, registers a
  `ViolationListener` bean that hands every violation to it; it keeps them only while a recording is active.
- `RecordedViolations` is resolved for test-method parameters of a `@BoundrailTest` class; `ContextAssertions.assertThatViolations()` reads the
  active recording. Both, called while no `@BoundrailTest` test method is running, throw `IllegalStateException` (TEST-02, D46). The
  Javadoc of `@BoundrailTest` says that running its test methods in parallel is not supported.
- The recorder's auto-configuration is the module's only one; `TestInboundTrustVerifier` and `peer()` stay out of it (TEST-01, TEST-02).
- `SnapshotAssert` and `ViolationsAssert` extend `AbstractAssert`; failure messages name keys and kinds, never values of keys that are not
  loggable.

### Integration tests

- **Topology additions (test code, each in the first task that needs it).**
  - T011: edge declares `taskExecutor`, a `ThreadPoolTaskExecutor` from Boot's `ThreadPoolTaskExecutorBuilder` with one thread (so IT-1.6's
    later task reuses it), and sets `spring.task.execution.propagate-context=true`, so Boot applies Micrometer's decorator. `@EnableAsync`, an
    `EdgeAsyncWork` bean whose `@Async` method calls orders (a separate bean, because a self-call bypasses the proxy), and an endpoint that
    waits on its future. With several `TaskExecutor` beans, `@Async` uses the bean named `taskExecutor`.
  - T014: `rawExecutor`, a one-thread `ExecutorService` whose threads are named `rawExecutor-N`, and an endpoint that runs the same work on it
    and returns the `ContextViolationException`'s violation fields (kind, key, boundary, capture site, executor evidence) or `sent`.
    `Topology.Running.startEdge(String... properties)` starts an extra edge instance with the common properties plus the given ones, for
    IT-1.3 (T015), IT-1.4 and IT-1.5 (T015); the test closes it. The shared topology starts once per run, before any test's output capture,
    so a scenario that asserts startup log entries or another `violations.mode` starts its own edge.
  - T015: `boundExecutor`, `BoundExecutors.detached(...)` around a one-thread `ExecutorService`, and an endpoint using it.
  - T017: a console pattern with `tenant=%X{tenant}` on every service (logging is initialised once per JVM, by the first service).
  - T019: `@EnableScheduling` and a `cleanupJob` bean whose `@Scheduled(fixedDelay = …)` method `run` records every bound key of
    `Context.capture()` to `ObservedContext`.
  - T020/T021: an edge `DeferredResult` endpoint completed from a thread with no context, a `Callable` endpoint that calls orders, and a
    `HandlerInterceptor` (registered through a `WebMvcConfigurer`) that records the bound `tenant` in `postHandle` of an `ASYNC` dispatch.
  - T023: `@BoundrailTest` on `ContextPropagationIT`; every service has the recorder through the `-test` auto-configuration on the classpath.
- **Shared JVM.** Counters, observations and log output are shared, so each scenario asserts deltas, uses a marker header, or uses its own
  extra edge. Extra edge instances register and remove their own MDC listener (LOG-01).

### Verify commands

Unit: `./gradlew :<project>:test --tests '<Class>'`. Scenarios (Gradle applies `--tests` to the task just before it):

```bash
./gradlew --continue :integration-tests:integrationTestJdk21 --tests '*ContextPropagationIT' \
          :integration-tests:integrationTestJdk25 --tests '*ContextPropagationIT'
```

RC-9 runs in all three tasks:

```bash
./gradlew --continue :integration-tests:integrationTestJdk21 --tests '*RuntimeConformanceIT' \
          :integration-tests:integrationTestJdk25 --tests '*RuntimeConformanceIT' \
          :integration-tests:integrationTestJdk25ThreadLocal
```

Every task ends with `./gradlew check`.

## Spec Gaps

None open. G2 – G14 are resolved in spec.md (RT-07, RT-08, EXE-01, EXE-06, DIAG-05, DIAG-07, DIAG-11, DIAG-12, TEST-02), in
001-foundation's Test Topology (`startEdge`) and in design.md D46. G1 is closed by the user's decision after T001: reflection on the private
fields is allowed and a missing field fails startup (EXE-10), EXE-06 covers ordinary `@Scheduled` methods only and replaces only Spring's own
post-processor definition (else one WARN), and EXE-07's Javadoc also says that reactive and Kotlin `suspend` `@Scheduled` methods get no
system context (D47). Their tests are planned in T003, T004, T008, T012, T013 – T016, T019, T021, T023 and T024.

The `boundrail-conventions` skill now says `DEADLINE` is never put in the MDC (LOG-02); the fix is done.

## Guard Tests

These tests already pass before the task that introduces them. As in 002 and 003, each red is shown against a named temporary change, which is
reverted before the commit. They are not TDD exceptions, but the user is asked to accept them with the plan.

A test that is green before its task is accepted as a guard when its red is shown against a named temporary change to the finished code, which
is then reverted; the leader reports it in the task report, the planner adds it to this table, and the reviewer checks it.

Every other negative assertion in this plan sits in the same test as a positive assertion that is red before its task (for example, "EMPTY has
no capture site" sits with "a binding records its site").

| Task | Scenario or test | Why green first | Red shown against (temporary, reverted) |
|---|---|---|---|
| T008 | `RT-09 violation log rate limiting belongs to one application context` | Each context already has its own `ViolationReporter` bean | `ViolationReporter.lastLogged` made `static` |
| T011 | `EXE-04 a plain Executor or ExecutorService bean is not wrapped automatically` | Before T011 nothing touches executor beans, and T011's post-processor matches only the three EXE-03 types | The post-processor replacing every other `ExecutorService` bean with `BoundExecutors.detached(bean)` |
| T003 | `RT-07 an Error from a later onEnter stops the entry: the operation and later listeners do not run, earlier restorers run in reverse order, and the Error propagates` (added during T003's review; accepted) | T003's code already unwinds the restorers already returned on any throwable from `onEnter` | The unwinding limited to the `null`-restorer path, so an `Error` from `onEnter` propagates without calling the earlier restorers |
| T014 | IT-1.4 Report mode continues | `report` mode (DIAG-02) and rate-limited logging (DIAG-03) exist since M2; the report-mode edge only needs `rawExecutor` | `ViolationReporter.dueForLog` returning `true` always, so two WARN entries are logged |
| T015 | `DIAG-06 with audit.enabled=false nothing is audited and executor evidence is still filled` | T014's table does not depend on the audit, and T015's listener is conditional from its first green step | The `audit.enabled` condition removed from the audit listener |
| T016 | `DIAG-05 a context that sets neither property leaves the JVM-wide mode unchanged` | Before T016 Spring applies nothing; T016 applies only properties that are set | The capture-site bean applying `off` and `0.01` when the properties are unset |
| T017 | `LOG-01 without SLF4J no MDC listener is registered` | The nested configuration is `@ConditionalOnClass(MDC.class)` from its first green step | `@ConditionalOnClass` removed from the MDC configuration |
| T020 | `IN-09 ContextAuthenticationFilter does not run on ASYNC dispatches` | `OncePerRequestFilter` skips async dispatches by default | `shouldNotFilterAsyncDispatch()` overridden to return `false` in `ContextAuthenticationFilter` |
| T020 | `IN-09 an ASYNC dispatch with no stored snapshot runs in the current scope` | Before T020 the inbound filter skips async dispatches, so the chain runs in the current scope | The inbound filter running the chain in `ContextSnapshot.EMPTY` when no snapshot is stored |
| T023 | `TEST-02 the extension does not change the violation mode` | Nothing sets the violation mode | The extension setting the system property `boundrail.context.violations.mode=report` before the class and clearing it after |
| T023 | `TEST-01 neither the verifier nor peer() is auto-configured` (rewritten) | T023's auto-configuration declares only the recorder | A `TestInboundTrustVerifier` `@Bean` added to the `-test` auto-configuration |

## TDD Exceptions

Both rows are approved. EXE-07's Javadoc statement now also covers reactive and Kotlin `suspend` `@Scheduled` methods (amended EXE-07,
D47); it stays in the first row.

| Task | What | Verified by |
|---|---|---|
| T009, T010 | The Javadoc statements EXE-04 (overloads without a policy filter with `BuiltInKeys.defaultPolicy()`, so application rules at `ATTACHED` and `DETACHED` are not applied), EXE-07 (common-pool `CompletableFuture.*Async` not covered; reactive and Kotlin `suspend` `@Scheduled` methods get no system context) and DIAG-07 (the audit sees only beans, and only those DIAG-12 covers; runtime validation covers executors created in code). They are documentation, with no behaviour a test can fail on. Approved | Review of T009 and T010, and inspection in T025 against each requirement's wording |
| T023 | The Javadoc statement of TEST-02 that running `@BoundrailTest` test methods in parallel is not supported | Review of T023, and inspection in T025 |

T001 is a spike, not an exception: no code from it is kept. Every dependency, catalog entry and the `-test` imports file is part of the green
step of a task with a failing test ([Build](#build)).

## Project Structure

### Documentation (this feature)

```text
specs/004-async-diagnostics/
├── spec.md
├── plan.md                # this file
├── tasks.md
└── contracts/runtime-listeners.md  contracts/executors-and-test.md
```

### Source Code (repository root)

```text
gradle/libs.versions.toml                                           # T011, T017, T020, T022
boundrail-context-core/src/main/java/io/github/boundrail/context/
├── ScopeListener.java                                              # T002
├── ContextRuntime.java (listeners, enter, captureSites)            # T002 – T004
├── ContextSnapshot.java  ImmutableContextSnapshot.java (captureSite)   # T005
├── ContextBinding.java (enter, records capture site)  ContextPolicy.java (filter keeps it)   # T002, T005
├── CaptureSiteRecorder.java (package-private)                      # T005, T006
├── BoundExecutors.java  BoundExecutor.java  BoundExecutorService.java (package-private)   # T009, T010
└── internal/CaptureSiteProperties.java  internal/ModeExecutor.java # T004, T015
boundrail-context-core/src/test/java/io/github/boundrail/context/
├── ScopeListenerTest.java  CaptureSiteModeTest.java  CaptureSiteRecorderTest.java  BoundExecutorsTest.java
├── internal/CaptureSitePropertiesTest.java
└── diagnostics/CaptureSiteTest.java                                # package outside the library's packages
boundrail-context-spring/build.gradle.kts                           # T017, T020
boundrail-context-spring/src/main/java/io/github/boundrail/context/spring/
├── ViolationReporter.java (violation(...), listeners, evidence)  ViolationListener.java   # T007, T008, T014
├── BoundrailContextAutoConfiguration.java  BoundrailContextProperties.java (executors, diagnostics, audit)
├── ExecutorDecorator.java  ExecutorDecoratingPostProcessor.java (package-private)        # T011 – T013
├── ExecutorTable.java  ExecutorAudit.java (package-private)        # T014, T015
├── CaptureSiteSettings.java (package-private)                      # T016
├── MdcScopeListener.java (package-private)                         # T017, T018
├── ScheduledContextRegistrar.java  ContextScheduledAnnotationBeanPostProcessor.java (package-private)   # T019
├── ContextInboundFilter.java  ContextAuthenticationFilter.java (ASYNC)   # T020
└── MvcAsyncExecutorPostProcessor.java  AttachedAsyncTaskExecutor.java (package-private)   # T021
boundrail-context-spring/src/test/java/io/github/boundrail/context/spring/
├── ViolationEvidenceTest.java  ViolationListenerTest.java  ExecutorDecorationTest.java  ExecutorModeTest.java
├── ExecutorTableTest.java  ExecutorAuditTest.java  CaptureSiteSettingsTest.java  MdcScopeListenerTest.java
├── ScheduledContextTest.java  AsyncDispatchTest.java  MvcCallableTest.java
boundrail-context-test/build.gradle.kts                             # T022, T023
boundrail-context-test/src/main/java/io/github/boundrail/context/test/
├── BoundrailTest.java  BoundrailTestExtension.java  RecordedViolations.java  ViolationRecorder.java   # T023
├── BoundrailTestAutoConfiguration.java                              # T023
├── ContextAssertions.java  SnapshotAssert.java  ViolationsAssert.java   # T022, T024
boundrail-context-test/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports   # T023
boundrail-context-test/src/test/java/io/github/boundrail/context/test/
├── SnapshotAssertTest.java  BoundrailTestExtensionTest.java  ViolationsAssertTest.java  TestInboundTrustVerifierTest.java (T023)
integration-tests/build.gradle.kts                                  # T011
integration-tests/src/test/java/io/github/boundrail/context/it/
├── RuntimeConformanceIT.java (RC-9)  ContextPropagationIT.java (IT-1.1 – IT-1.9)  BuildSmokeIT.java (T022, T023)
└── topology/EdgeApp.java  EdgeAsyncWork.java  EdgeExecutors.java  CleanupJob.java  ObservedContext.java  Topology.java (startEdge)
```

**Structure Decision**: Contract types go where the contracts put them. Everything else in `-spring` is package-private. The one public
non-contract type, `internal.ModeExecutor`, exists so `-spring` can recognise `BoundExecutors` wrappers, as `internal.ContextSlot` already
crosses packages.

## Requirement Coverage

| Requirement or scenario | Tasks |
|---|---|
| CORE-14 | T002 (listener registration), T003 (`null` restorer), T004 (`captureSites`), T009, T010 (`BoundExecutors`), T022 (`assertThat`, `SnapshotAssert`), T024 (`ViolationsAssert`) |
| RT-07 | T002 (order, after the snapshot is current, restore on exception), T003 (throwing listeners and restorers, the restorer's WARNING names its listener, `Error` from `onEnter` or a restorer, `null` restorer, duplicate add, unknown remove) |
| RT-08 | T004 |
| RT-09 | T008 (per-context rate limiter), T023 (recorder is the one exception), T025 (inspection) |
| IN-09 | T020 |
| EXE-01 | T013 (decorated beans only, case-insensitive bean names), T015 (a `BoundExecutors` bean keeps its own mode in the audit) |
| EXE-02 | T009, T010 (`BoundExecutors`), T011 (decorated, effective policy), T013 (`ATTACHED`) |
| EXE-03 | T011 (installed before initialisation, three types, `auto-decorate`), T012 (composition, library outermost, never wraps itself) |
| EXE-04 | T009, T010, T011 (plain executors not wrapped), T015 (recognised by the audit) |
| EXE-05 | T011 (IT-1.1), T013 (`@Async` on an attached executor) |
| EXE-06 | T019 (scheduler invocations; direct calls keep the caller's context; only Spring's own definition replaced, else WARN; reactive and `suspend` methods excluded) |
| EXE-07 | T009 (Javadoc, TDD exception; including reactive and `suspend` `@Scheduled` methods) |
| EXE-09 | T021 (`Callable`, `WebAsyncTask` with callbacks, MVC's default executor) |
| EXE-10 | T012 (`taskDecorator` on the three executor types: pinned, read once, missing field fails startup, also under lazy initialization), T021 (`RequestMappingHandlerAdapter.taskExecutor`: the same) |
| DIAG-04 | T007 (thread, capture site, every adapter, WARN), T014 (executor evidence, longest prefix, before start) |
| DIAG-05 | T005 (`on`, `off`), T006 (`sampled`), T016 (properties) |
| DIAG-06 | T015 |
| DIAG-07 | T009 (Javadoc, TDD exception) |
| DIAG-10 | T005 |
| DIAG-11 | T008 |
| DIAG-12 | T014 (table), T015 (audit) |
| LOG-01 | T017, T018 |
| LOG-02 | T017 |
| TEST-02 | T023 (`IllegalStateException` outside a test method; parallel Javadoc, TDD exception pending approval), T024 (`assertThatViolations()` outside a test method) |
| TEST-03 | T022 (`SnapshotAssert`), T024 (`ViolationsAssert`) |
| TEST-04 | T022 |
| RC-9 | T003 |
| IT-1.1 | T011 |
| IT-1.2 | T014 |
| IT-1.3 | T015 |
| IT-1.4 | T014 (guard) |
| IT-1.5 | T015 |
| IT-1.6 | T017 |
| IT-1.7 | T019 |
| IT-1.8 | T023 |
| IT-1.9 | T021 |
| SC-001 – SC-003 | T025 (SC-003 also T005) |

## Risks

- **Composing with existing decorators** (roadmap M3). Spring exposes no getter, so the library reads private fields (T001, G1). IT-1.1 runs
  with Micrometer's decorator from T011, and T012 proves that decorator still works inside the library's scope.
- **Reflection on framework internals (EXE-10).** A Spring upgrade can rename `taskDecorator` or `taskExecutor`. T012 and T021 pin each field
  by name and type, and a missing field fails startup rather than silently skipping composition or EXE-09.
- **Replacing a framework bean definition (EXE-06).** A later Spring version may change how `@EnableScheduling` registers its post-processor.
  T019 pins the definition's factory bean and method; any other form is left alone with a WARN.
- **Early bean creation.** A `BeanPostProcessor` that injects beans directly forces them, and the application's `ContextPolicy` beans, to be
  created before post-processing. The post-processor is a `static` bean method with `ObjectProvider`s resolved on first use.
- **JVM-wide state in a shared test JVM.** Capture-site mode, listeners and the recorder are shared by every suite in an integration test task
  and every test in a module's test JVM. Tests that change the mode restore it; tests that register a listener remove it in `finally`; RC-9
  asserts only its own listeners' calls, and they record only scopes entered on the test's own thread, since the shared topology's
  services enter scopes on other threads, and register their own MDC listeners, while it runs.
- **Log assertions.** IT-1.4 counts WARN entries of its own extra edge, and IT-1.6 depends on the console pattern being set by the first
  service started in the JVM. IT-2.8's assertions on log lines must still hold with `tenant=` in the pattern (T017 runs TrustBoundaryIT).
- **Timing.** IT-1.7 waits for the scheduled job with a bounded poll, not a sleep.

## Complexity Tracking

No constitution violations to justify.
