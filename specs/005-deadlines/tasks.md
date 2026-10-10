---
description: "Task list for 005-deadlines"
---

# Tasks: Deadlines

**Input**: Design documents from `/specs/005-deadlines/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [contracts/deadline-api.md](contracts/deadline-api.md); 004-async-diagnostics
merged (0a1930d), `main` at 1d31eda

**Tests**: Required (constitution principle VII). Each task is one red-green cycle: its failing test, with the behaviour it pins down, and the
minimum code that makes it pass, committed and reviewed together. Every task leaves `./gradlew check` green. Every test's display name starts
with the requirement or scenario ID it covers.

- Approved by the user on 2026-10-09: these tasks, the plan, the TDD exceptions for the Javadoc statements of DL-07 and OUT-05 (T011),
  of EXE-08 (T013) and of G2's `Callable` note (T014), and the guard tests in T010 and T012 ([plan.md](plan.md#tdd-exceptions),
  [plan.md](plan.md#guard-tests)).
- T001, the spike, is done; its outcome is in [plan.md](plan.md#spike-t001). Every spec gap is closed: G1 as option A, G2 – G6 with the
  recommended answers, written into spec.md and design.md by the spec-creator ([plan.md](plan.md#spec-gaps)).
- Two guard tests pass before their task: one each in T010 and T012. Each red is shown against a temporary code change that is reverted
  ([plan.md](plan.md#guard-tests)).
- A test that is green before its task is accepted as a guard when its red is shown against a named temporary change to the finished code,
  which is then reverted; the leader reports it in the task report, the planner adds it to plan.md's Guard Tests table, and the reviewer
  checks it. A negative assertion in the same test as a positive assertion that is red before the task is not a guard.
- Review follow-ups get a letter suffix (T004a, …).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: The user story the task belongs to (US1: the budget travels and shrinks; US2: expired work fails fast; US3: deadlines stop at
  detached boundaries)

Path abbreviations:

| Short | Path |
|---|---|
| `core/` | `boundrail-context-core/src/main/java/io/github/boundrail/context/` |
| `coretest/` | `boundrail-context-core/src/test/java/io/github/boundrail/context/` |
| `spring/` | `boundrail-context-spring/src/main/java/io/github/boundrail/context/spring/` |
| `springtest/` | `boundrail-context-spring/src/test/java/io/github/boundrail/context/spring/` |
| `it/` | `integration-tests/src/test/java/io/github/boundrail/context/it/` |
| `topology/` | `integration-tests/src/test/java/io/github/boundrail/context/it/topology/` |

Verify commands:

- **Unit**: `./gradlew :<project>:test --tests '<TestClass>'` for red and green, then `./gradlew check` before commit.
- **IT**: the scenario run, then `./gradlew check` before commit:

  ```bash
  ./gradlew --continue :integration-tests:integrationTestJdk21 --tests '*DeadlinePropagationIT' \
            :integration-tests:integrationTestJdk25 --tests '*DeadlinePropagationIT'
  ```

- **IT+**: the IT run plus `--tests '*ContextPropagationIT'` and `--tests '*TrustBoundaryIT'` on both tasks, for tasks that change shared
  topology code.

Enabling a scenario means: remove `@Disabled`; keep its `@DisplayName` and the comment with the spec text; write the body. Scenarios use a
marker and assert deltas on DeadlinePropagationIT's own edge, never totals, because the topology is shared.

"Lazy initialization" in a test means the setup first used in 003's T006a (`spring.main.lazy-initialization=true` and a
`LazyInitializationBeanFactoryPostProcessor`).

## Phase 0: Spike

- [x] T001 Spike: per-request timeout on the JDK `HttpClient` factory, factory detection, exceptions through the clients, the `Callable` hook,
  `sendError` at edge, blank durations.
  - Done; no code kept, nothing committed. Outcome in [plan.md](plan.md#spike-t001): DL-07 uses option A (an interceptor timer that
    interrupts the calling thread, with a `ReentrantLock` handshake; the JDK client cancels cleanly); Boot's factory is detected with
    `instanceof JdkClientHttpRequestFactoryBuilder` (a wrapped builder can give a false WARN, documented); `DeadlineExceededException`
    reaches `RestClient` and `RestTemplate` callers unwrapped; a throwing `preProcess` refuses the `Callable` on Tomcat, with the library's
    configurer ordered last; `sendError(504)` survives edge's resource-server chain; blank durations are bound as `String`s. The user
    accepted the recommendations (G1 closed).

---

## Phase 1: Setup

None. 001 – 004 built the projects, the integration test tasks, the topology, the auto-configuration and the disabled scenarios. A dependency,
if one is needed, is added in the green step of the first task whose test needs it.

---

## Phase 2: Foundational

- [x] T002 [P] Public constructors of `DeadlineExceededException`.
  - Red, in `coretest/DeadlineExceededExceptionTest.java`:
    - `DL-12 the (Stage) constructor is public and sets the message and the stage`: `DeadlineExceededException.class.getConstructor(Stage.class)`
      exists (red: it is package-private); for each stage, the message is `Deadline exceeded at stage <NAME>`, `stage()` returns it and
      `getCause()` is `null`;
    - `DL-12 the (Stage, Throwable) constructor is public and also sets the cause`: the same message and stage, `getCause()` is the given
      cause;
    - `CORE-15 a null stage or cause throws NullPointerException`: `(null)`, `(null, cause)` and `(stage, null)`.
  - Green: both constructors public in `core/DeadlineExceededException.java`, each argument checked with `Objects.requireNonNull` before
    `super` ([plan.md](plan.md#core)).
  - Completes: DL-12; CORE-15.
  - Verify: Unit `DeadlineExceededExceptionTest` in `:boundrail-context-core`.
- [x] T003 The `deadline.*` properties: binding and startup validation.
  - Red, in `springtest/DeadlinePropertiesTest.java` (`ApplicationContextRunner`, reading `BoundrailContextProperties.deadline()`):
    - `DL-03 deadline properties default to no default-inbound, a max-inbound of 30s, expired-status 504 and map-exception true`;
    - `DL-03 default-inbound and max-inbound are bound as Durations with Spring Boot's binding`: `250ms`, `PT2S`, `5` (milliseconds);
    - `DL-03 a blank default-inbound counts as not set, and a blank max-inbound is the default 30s`: `` and `   ` for each, through a
      `MapPropertySource` (Boot itself fails on the whitespace-only duration, T001 item 6);
    - `DL-03 default-inbound may be greater than max-inbound`: `60s` with `30s` starts and binds both;
    - `DL-03 a default-inbound or max-inbound that is zero, negative or not a duration fails startup, also with lazy initialization`
      (parametrized): `0`, `-1s`, `abc` for each property, with and without lazy initialization;
    - `DL-04 expired-status is from 400 to 599, and any other value or a non-integer fails startup, also with lazy initialization`: `400`
      and `599` start; `399`, `600` and `abc` fail;
    - `DL-08 map-exception is bound as a Boot boolean, and a value Boot cannot convert fails startup, also with lazy initialization`:
      `yes`, `ON`, `1` are `true`; `no`, `off`, `0` are `false`; `maybe` fails.
  - Green: the `Deadline` record in `spring/BoundrailContextProperties.java`, with `default-inbound` and `max-inbound` bound as `String`s and
    converted with `ApplicationConversionService` (blank: unset, or `30s`), and its compact-constructor checks ([plan.md](plan.md#spring-wiring)).
  - Completes: DL-03 (property binding and validation); DL-04 (`expired-status`); DL-08 (`map-exception`); the Configuration section's startup
    failures.
  - Verify: Unit `DeadlinePropertiesTest` in `:boundrail-context-spring`.

**Checkpoint**: the contract and the properties exist; nothing reads them yet.

---

## Phase 3: User Story 1 - The time budget travels and shrinks (Priority: P1) 🎯 MVP

**Goal**: Trusted callers set the budget, capped; the edge applies a default; each hop sends the remaining time, rounded down.

**Independent Test**: IT-3.1, IT-3.5 and IT-3.6 in the IT command.

- [x] T004 [US1] Inbound deadline from a trusted header, the cap and the default; enable IT-3.5 and IT-3.6.
  - Red:
    - in `springtest/InboundDeadlineTest.java` (`WebApplicationContextRunner` with the library's filter, as `ContextInboundFilterTest` does;
      the chain records `Deadlines.current()`):
      - `DL-03 a trusted Boundrail-Deadline-Ms binds Deadline.after(value)`: `2000` gives a remaining time above 1.5s and at most 2s;
      - `DL-03 a trusted value is capped by max-inbound, also when it is too large for a long`: `600000` and `99999999999999999999` give at
        most 30s, and at most 1s with `max-inbound=1s`;
      - `DL-03 with no header, default-inbound is bound if set, and may exceed max-inbound; otherwise no deadline is bound`:
        `default-inbound=60s`, `max-inbound=30s`: more than 30s remaining; without `default-inbound` no deadline is bound;
      - `DL-03 an untrusted Boundrail-Deadline-Ms is ignored and reported once as UNTRUSTED_HEADER, and default-inbound applies if set`: with
        `default-inbound=500ms`, `Boundrail-Deadline-Ms: 5` from an untrusted caller gives more than 5ms and at most 500ms; without a default
        no deadline is bound; one `UNTRUSTED_HEADER` for `boundrail.deadline` in each case;
      - `DL-03 the deadline is bound in the same scope as the IN-02 keys, so the stored snapshot holds it`: the snapshot under
        `SNAPSHOT_ATTRIBUTE` holds both the deadline and `tenant`, and an `ASYNC` dispatch (`DeferredResult`, `asyncDispatch`) sees the same
        `Deadline` instance;
    - narrow `IN-02 a trusted request binds no deadline and no authority from headers` in `springtest/ContextInboundFilterTest.java` to
      `IN-02 a trusted request binds no authority from headers`; its deadline assertion is replaced by the DL-03 tests above (an existing test
      narrowed, not a new one, so not a guard);
    - enable IT-3.5: a trusted caller sends `Boundrail-Deadline-Ms: 600000` to orders' `/ping` with a marker; orders' observation has
      `ctx.deadline-remaining-nanos` at most 30s;
    - enable IT-3.6: a public caller sends `Boundrail-Deadline-Ms: 5` with alice's JWT to the class's edge `/ping`; status 200; the
      observation's remaining time is above 5ms and at most 500ms; the edge's own `boundrail.context.violations` counter for
      `untrusted_header`/`boundrail.deadline` rose by one ([plan.md](plan.md#integration-tests), IT-3.6 robustness).
  - Green:
    - in `spring/ContextInboundFilter.java`, the valid-header path, the cap and the default, joining the IN-02 binding; the settings passed
      from `spring/BoundrailContextAutoConfiguration.java` ([plan.md](plan.md#spring-wiring));
    - topology test code: `ctx.deadline-remaining-nanos` in `topology/ObservedContext.java`; in `it/DeadlinePropagationIT.java`,
      `@ExtendWith(Topology.class)`, the extra edge started in `@BeforeAll` with `default-inbound=500ms` and
      `executors.modes.attachedExecutor=attached` (G5: the shared edge keeps no default), closed in `@AfterAll`, the warm-up
      request, and the trusted test client.
  - Completes: DL-03 (valid header, cap, default, untrusted, scope); IT-3.5; IT-3.6.
  - Verify: Unit `InboundDeadlineTest` and `ContextInboundFilterTest`; IT.
- [x] T005 [US1] Invalid, repeated and oversized trusted deadlines.
  - Red, in `springtest/InboundDeadlineTest.java`:
    - `DL-03 a trusted value that is not one or more ASCII digits binds nothing, is reported once as INVALID_VALUE, and default-inbound
      applies` (parametrized): `abc`, `-1`, `+5`, `1.5`, ``, ` 5`, `5 `, `１２` (full-width digits); with `default-inbound=500ms` the default is
      bound, without it nothing; one `INVALID_VALUE` for `boundrail.deadline` each, without the value (DIAG-09);
    - `DL-03 a trusted header on more than one line binds nothing, is reported once as INVALID_VALUE, and default-inbound applies`:
      `5000` and `6000`, and `5000` with a second line longer than `max-value-length`, which is still one `INVALID_VALUE` and no
      `OVERSIZED_VALUE` (G6);
    - `DL-03 a trusted value longer than wire.max-value-length is reported as OVERSIZED_VALUE, and default-inbound applies`: with
      `max-value-length=3`, `5000` gives one `OVERSIZED_VALUE` and no `INVALID_VALUE`, and `500` is bound;
    - replace `IN-02 a repeated Boundrail-Deadline-Ms on a trusted request is skipped, not reported` in `ContextInboundFilterTest`, which M4
      contradicts, by the repeated-line test above;
    - in `ContextInboundFilterTest`'s `WIRE-04 a trusted value longer than max-value-length is not bound and is reported as OVERSIZED_VALUE`,
      change the deadline assertion from "skipped, no violation" to one `OVERSIZED_VALUE` for `boundrail.deadline` (M4 contradicts it).
  - Green: the invalid-value and repetition paths fall back to the default ([plan.md](plan.md#spring-wiring)).
  - Completes: DL-03 (invalid, repeated, oversized).
  - Verify: Unit `InboundDeadlineTest` and `ContextInboundFilterTest`.
- [x] T006 [US1] Expired on arrival; enable IT-3.4.
  - Red:
    - in `springtest/InboundDeadlineTest.java`:
      - `DL-04 a request whose deadline is already expired gets expired-status with an empty body, without sendError, and the chain is not
        invoked`: trusted `Boundrail-Deadline-Ms: 0`; status 504, content length 0, the response not committed by the filter (no `sendError`), and
        the chain's servlet was not called;
      - `DL-04 expired-status is configurable`: `expired-status=503` gives 503;
      - `DL-04 the check is made once, on the initial dispatch`: a `DeferredResult` request with a 50ms deadline completes after the deadline
        has expired; the `ASYNC` dispatch runs and the response is 200 (sits with the red 504 assertion of the initial dispatch, so not a
        guard);
    - enable IT-3.4: a trusted caller sends `Boundrail-Deadline-Ms: 0` to orders' `/ping` with a marker; status 504; orders has no
      observation with the marker.
  - Green: the expiry check after binding, in the initial-dispatch branch of `ContextInboundFilter` ([plan.md](plan.md#spring-wiring)).
  - Completes: DL-04; IT-3.4.
  - Verify: Unit `InboundDeadlineTest`; IT.
- [x] T007 [US1] Remaining time on internal calls; enable IT-3.1.
  - Red:
    - in `springtest/OutboundDeadlineTest.java` (`RecordingServer`, a client from the auto-configured `RestClient.Builder`):
      - `DL-05 an internal call with a deadline bound carries Boundrail-Deadline-Ms with the remaining milliseconds, never more than the
        sender's`: with a 10s deadline the value is digits, at most 10000 and at least 9000; an external call and an unknown call carry no
        such header;
      - `DL-05 the remaining time is rounded down to a whole millisecond`: the package-private `headerValue(Duration)` gives `1` for
        1,999,999ns, `0` for 999,999ns and `0` for 1ns;
      - `DL-05 an application's valid smaller value is kept, a larger one is replaced by the remaining time, and an invalid one is replaced`:
        app `100` with a 10s deadline is sent as `100`; app `60000` is sent as the remaining time; app `abc`, and two values `100` and `200`,
        are sent as the remaining time;
      - `DL-05 with no deadline bound an application's Boundrail-Deadline-Ms is left unchanged`: app `abc` is sent as `abc` (sits with the red
        replacement assertion above in one test, so not a guard);
      - `DL-05 WIRE-04 applies to the value written`: with `max-value-length=2` and a 10s deadline, no `Boundrail-Deadline-Ms` is sent
        and one `OVERSIZED_VALUE` for `boundrail.deadline` is reported; an application-set `Boundrail-Deadline-Ms: 100` on the same call is
        removed too, so none is sent (G3);
    - narrow `OUT-04 an internal call writes no Authorization and no Boundrail-Deadline-Ms` in `ContextClientInterceptorTest` to `OUT-04 an
      internal call writes no Authorization`; its deadline assertion is replaced by the DL-05 tests above;
    - enable IT-3.1: a public request with alice's JWT to the class's edge `/deadline/chain`; edge, orders and billing each record on
      arrival, and their `ctx.deadline-remaining-nanos` satisfy `500ms ≥ edge > orders > billing > 0`.
  - Green:
    - the DL-05 step in `spring/ContextClientInterceptor.java`, after the headers are written and immediately before `execute`
      ([plan.md](plan.md#spring-wiring));
    - topology test code: `Topology` registers `topology` for orders and billing; `OrdersApp` `/call-billing` with an internal billing client;
      `EdgeApp` `/deadline/chain`; the IT-3.1 warm-up request.
  - Completes: DL-05; IT-3.1.
  - Verify: Unit `OutboundDeadlineTest` and `ContextClientInterceptorTest`; IT+.

**Checkpoint**: IT-3.1, IT-3.4 (US2's inbound part), IT-3.5 and IT-3.6 green on JDK 21 and JDK 25.

---

## Phase 4: User Story 2 - Expired work fails fast (Priority: P1)

**Goal**: An expired call is never sent; a slow call ends with the budget; escaping expiries become the configured status.

**Independent Test**: IT-3.2 – IT-3.4 in the IT command (IT-3.4 enabled in T006).

- [x] T008 [US2] Fail fast before sending.
  - Red, in `springtest/OutboundDeadlineTest.java`:
    - `DL-06 an expired deadline throws DeadlineExceededException at BEFORE_SEND and the request is not sent, to any destination`: internal,
      external and unknown destinations; the `RecordingServer` records nothing; the exception reaches the caller unwrapped through
      `RestClient` and through `RestTemplate` (T001 item 3);
    - `DL-06 the expiry check comes before the required-key check, so no violation is reported and no header is written`: the topology
      policy's required `tenant` unbound and an expired deadline: `BEFORE_SEND`, no `MISSING_REQUIRED` reported, in `fail` and `report` modes.
  - Green: the check at the start of `ContextClientInterceptor.intercept` ([plan.md](plan.md#spring-wiring)).
  - Completes: DL-06.
  - Verify: Unit `OutboundDeadlineTest`.
- [x] T009 [US2] Map an escaping `DeadlineExceededException`; enable IT-3.3.
  - Red:
    - in `springtest/DeadlineExceptionMappingTest.java` (`MockMvc` on a `WebApplicationContextRunner` with the auto-configuration):
      - `DL-08 a DeadlineExceededException escaping a handler gets expired-status through sendError`: status 504, set through
        `sendError` (the response is committed by the resolver); `expired-status=503` gives 503; with `map-exception=false` the exception
        is not mapped and propagates;
      - `DL-08 an application @ExceptionHandler for the exception wins`: a controller advice answering 418 gives 418, while the same exception
        from a controller it does not cover gives 504;
      - `DL-08 only the exception itself is mapped`: a `RuntimeException` whose cause is a `DeadlineExceededException` is not mapped, while
        the bare exception is;
      - `DL-08 nothing is mapped once the response is committed`: a handler that writes and flushes, then throws, keeps its status, while an
        uncommitted one gets 504;
      - `DL-08 the resolver runs after the application's resolvers and is absent without Spring MVC`: its order is
        `Ordered.LOWEST_PRECEDENCE`; with `FilteredClassLoader(HandlerExceptionResolver.class)` no resolver bean exists;
    - enable IT-3.3: alice's request to the class's edge `/deadline/sleep-then-call-orders` with a marker; status 504; edge recorded the stage
      `BEFORE_SEND`; orders has no observation with the marker.
  - Green: `spring/DeadlineExceptionResolver.java` and its nested configuration in the auto-configuration ([plan.md](plan.md#spring-wiring));
    topology test code: the edge endpoint.
  - Completes: DL-08 (handler exceptions); IT-3.3.
  - Verify: Unit `DeadlineExceptionMappingTest`; IT.
- [x] T009a [US2] Register the DL-08 resolver through MVC's resolver chain (T009 review follow-up).
  - Red, in `springtest/DeadlineExceptionMappingTest.java`: `DL-08 the resolver is added to MVC's resolver chain, not as a
    HandlerExceptionResolver bean`: in a servlet `DispatcherServlet` setup without `@EnableWebMvc` and without Boot's MVC auto-configuration,
    an application `@ExceptionHandler` still handles its exception (red: T009's lone bean replaces `DispatcherServlet`'s default resolvers);
    with `@EnableWebMvc` an unhandled `DeadlineExceededException` still gets 504, after the application's handlers.
  - Green: a `WebMvcConfigurer` bean whose `extendHandlerExceptionResolvers` appends `DeadlineExceptionResolver`; no `HandlerExceptionResolver`
    bean; the `BoundrailClients` Javadoc note is part of T011's Javadoc (TDD exception row).
  - Completes: DL-08 (registration).
  - Verify: Unit `DeadlineExceptionMappingTest`; IT-3.3.
- [x] T010 [US2] Cap the response timeout on the JDK `HttpClient` factory until the response headers arrive (option A); enable IT-3.2.
  - Red:
    - in `springtest/DeadlineTimeoutTest.java` (auto-configured builder with `spring.http.clients.imperative.factory=jdk`; `RecordingServer`
      with a delayed response, and one that sends its headers at once and its body later, added to `support/RecordingServer.java`):
      - `DL-07 a call whose remaining time is shorter than the configured timeout fails with DeadlineExceededException at TIMEOUT within the
        remaining time plus 200ms, with the client's interrupt exception as the cause`: `read-timeout=10s`, a 300ms deadline, a 3s delay;
        internal and external destinations; the cause is the JDK client's `IOException` for the interrupt, not `HttpTimeoutException`; the
        calling thread is not left interrupted;
      - `DL-07 with no configured timeout the remaining time is the timeout`: no `read-timeout`, the same outcome;
      - `DL-07 a client's own shorter timeout propagates unchanged`: `read-timeout=200ms`, a 5s deadline, a 3s delay: the client's own
        exception (not `DeadlineExceededException`), as without a deadline (sits with the red `TIMEOUT` case in one parametrized test, so
        not a guard);
      - `DL-07 a timeout while reading the body propagates as the client's own exception` (G4): `read-timeout=1s`, a 300ms deadline, headers
        sent at once and the body after 3s: reading the body fails with the client's own exception, not `DeadlineExceededException` (a third
        case of the same parametrized test, so not a guard);
      - `DL-07 a call that completes within the remaining time is not affected`: a 20ms delay with a 300ms deadline returns 200, and the
        calling thread is not interrupted afterwards (also checked 500ms later). This is a guard (approved): green before T010, since nothing
        interrupts yet; show it red with the timer temporarily left armed after `execute` returns, then revert ([plan.md](plan.md#guard-tests));
    - in `springtest/DeadlineTimerTest.java`, through the package-private handshake with a stub execution:
      - `DL-07 a response that arrives as the time runs out is kept and no interrupt leaks`: the stub returns a response exactly when the
        timer fires (it waits until the timer has taken the lock); the interceptor returns that response, and the thread's interrupt status
        is clear afterwards; and a stub that returns just before the timer is due leaves no interrupt 500ms later;
      - `DL-07 an IOException or a RuntimeException after the timer fired becomes TIMEOUT, with it as the cause`: a stub that, on interrupt,
        throws `IOException("Request was interrupted")`, and one that throws `CancellationException` (as Apache HttpComponents can); each
        gives `DeadlineExceededException(TIMEOUT)` with that cause; the same exceptions thrown before the timer fires propagate unchanged;
      - `DL-07 the scheduler is a bean of the context and stops when it closes` (RT-09): after `close()` the timer's executor is shut down;
    - enable IT-3.2: a trusted caller sends `Boundrail-Deadline-Ms: 500` to billing's `/call-partner/slow` with a marker; status 504; billing
      recorded the stage `TIMEOUT` and an elapsed time no more than its recorded remaining time plus 200ms; partner's observation with the
      marker has no `boundrail-deadline-ms` header.
  - Green: `spring/DeadlineTimer.java` (per-context scheduler bean, `ReentrantLock` handshake) and its use in
    `spring/ContextClientInterceptor.java`, catching `IOException` and `RuntimeException` ([plan.md](plan.md#spring-wiring)); topology test
    code: partner `/slow`, billing `/call-partner/slow` with an `external()` client, and billing's `spring.http.clients.*` properties in `Topology`.
  - Completes: DL-07 (capping, `TIMEOUT`); IT-3.2 (with DL-05, DL-08 and OUT-04); SC-002.
  - Verify: Unit `DeadlineTimeoutTest`; IT+.
- [x] T010a [US2] Run uncapped when the timer is shut down (T010 review follow-up).
  - Red, in `springtest/DeadlineTimerTest.java`: `DL-07 a call made after the timer has shut down runs without the cap`: after `close()`,
    `execute` with a remaining time returns the execution's response instead of throwing `RejectedExecutionException` (red: T010 schedules
    on the shut-down scheduler).
  - Green: `DeadlineTimer.execute` calls the execution directly when the scheduler is shut down or rejects the task.
  - Completes: DL-07 (after context close; D51).
  - Verify: Unit `DeadlineTimerTest`.
- [x] T011 [US2] Startup WARN for other request factories; the `BoundrailClients` Javadoc.
  - Red, in `springtest/RequestFactoryCheckTest.java` (`ApplicationContextRunner` with Boot's HTTP client and `RestClient` auto-configurations;
    `OutputCaptureExtension`):
    - `DL-07 one WARN is logged at startup when Boot's auto-configured request factory is not the JDK HttpClient one, and DL-05 and DL-06
      still apply`: with `spring.http.clients.imperative.factory=simple`, exactly one WARN stating that timeout capping is not guaranteed
      and naming `spring.http.clients.imperative.factory`;
      through that client an internal call still carries `Boundrail-Deadline-Ms` and an expired one still throws `BEFORE_SEND`;
    - `DL-07 no WARN is logged with the JDK HttpClient factory, or without Boot's HTTP client auto-configuration`: `factory=jdk`; and a
      context without `ClientHttpRequestFactoryBuilder` (sits with the red WARN assertion above, so not a guard: one parametrized test).
  - Green: `spring/RequestFactoryCheck.java`, a `@Lazy(false)` bean in `ClientConfiguration` (`instanceof
    JdkClientHttpRequestFactoryBuilder`, T001 item 2); `compileOnly` on `spring-boot-http-client` with a catalog entry if the compile
    classpath lacks it; the Javadoc of `spring/BoundrailClients.java` for DL-07 (capping needs the JDK `HttpClient` request factory and is
    not guaranteed with others; clients given their own factory in code are not checked; the WARN can be wrong when an application
    customizer wraps Boot's builder) and OUT-05 (redirects): TDD exception, reviewed ([plan.md](plan.md#tdd-exceptions)). The false-WARN
    case is documented only, not tested.
  - Completes: DL-07 (WARN, Javadoc); OUT-05.
  - Verify: Unit `RequestFactoryCheckTest`.

**Checkpoint**: IT-3.1 – IT-3.6 green on JDK 21 and JDK 25.

---

## Phase 5: User Story 3 - Deadlines stop at detached boundaries (Priority: P2)

**Goal**: Attached work carries the deadline and refuses to start once it has passed; detached work never sees it.

**Independent Test**: IT-3.7 in the IT command.

- [x] T012 [US3] `BoundExecutors.attached` refuses expired tasks submitted with `submit`, `invokeAll` or `invokeAny`; enable IT-3.7.
  - Red:
    - in `coretest/BoundExecutorsTest.java`:
      - `EXE-08 a task submitted to an attached executor service after its deadline has expired is not run, and its future fails with
        DeadlineExceededException at EXECUTOR`: inside `Context.with(DEADLINE, Deadline.after(Duration.ZERO))`, `submit(Callable)`,
        `submit(Runnable)` and `submit(Runnable, result)`: the task's flag stays unset and `get` throws `ExecutionException` with that cause;
        a task submitted before expiry but started after it (one-thread pool blocked by a latch until the deadline has expired) is refused
        too; a task with time left runs;
      - `EXE-08 invokeAll and invokeAny refuse expired tasks`: each future of `invokeAll` fails as above; `invokeAny` throws
        `ExecutionException` with that cause;
      - `EXE-08 a detached executor service runs the task, because its snapshot holds no deadline`: `detached(...)` under the same expired
        deadline runs it (sits with the red attached assertion in one test, so not a guard);
    - in `springtest/ExecutorModeTest.java`: `EXE-08 a decorated executor in attached mode runs a task whose deadline has expired, and the
      task sees the deadline`. This is a guard: show it red with `ExecutorDecorator` temporarily throwing
      `DeadlineExceededException(EXECUTOR)` when the captured deadline has expired, then revert ([plan.md](plan.md#guard-tests));
    - enable IT-3.7: alice's request to the class's edge `/deadline/executors` with a marker: the `attachedExecutor` task recorded a deadline;
      the `taskExecutor` task recorded no deadline and `tenant=acme`; the task submitted to `boundAttachedExecutor` while its thread was
      blocked past the deadline did not run, and the endpoint returned `EXECUTOR` as the stage of the `ExecutionException`'s cause.
  - Green: the expiry check in `core/BoundExecutorService.java` for `submit`, `invokeAll` and `invokeAny` ([plan.md](plan.md#core));
    topology test code: edge's `attachedExecutor` and `boundAttachedExecutor` beans and `/deadline/executors`.
  - Completes: EXE-08 (`submit`, `invokeAll`, `invokeAny`; decorated executors still run expired tasks); IT-3.7 (with EXE-01, EXE-02,
    EXE-04, POL-04).
  - Verify: Unit `BoundExecutorsTest` and `ExecutorModeTest`; IT+.
- [x] T013 [US3] Expired tasks passed to `execute` go to the thread's `UncaughtExceptionHandler`.
  - Red, in `coretest/BoundExecutorsTest.java`:
    - `EXE-08 an expired task passed to execute is not run, its exception goes to the current thread's UncaughtExceptionHandler, and execute
      returns normally`: for `attached(Executor)` and `attached(ExecutorService)`, with a pool thread whose handler records; the handler
      receives `DeadlineExceededException(EXECUTOR)` on that thread, and the same pool thread then runs the next task (it survived);
    - `EXE-08 with an inline executor the caller's handler receives it and execute returns`: `attached(Runnable::run)`.
  - Green: the `execute` paths in `core/BoundExecutor.java` and `core/BoundExecutorService.java`; the Javadoc of `core/BoundExecutors.java`
    stating that executor beans the Spring module decorates in `ATTACHED` mode carry the deadline but run expired tasks: TDD exception,
    reviewed ([plan.md](plan.md#tdd-exceptions)).
  - Completes: EXE-08 (`execute`; Javadoc).
  - Verify: Unit `BoundExecutorsTest`.
- [x] T014 [US3] An expired MVC `Callable` is not called (check-only `preProcess`, registered through a `WebMvcConfigurer`, G2).
  - Red, in `springtest/MvcCallableTest.java` (`MockMvc`, `asyncDispatch`):
    - `EXE-08 a Callable that starts after its captured deadline has expired is not called, and the request completes with the exception,
      which DL-08 maps`: the request's 50ms deadline (bound around the request, as the filter does) expires while MVC's one-thread executor is
      blocked; the `Callable`'s flag stays unset, the concurrent result is `DeadlineExceededException(EXECUTOR)`, and the async dispatch
      answers 504; a `Callable` with time left runs and answers 200;
    - `EXE-08 the same holds for a WebAsyncTask without an executor of its own`;
    - `EXE-08 the check is registered last, after the application's own Callable interceptors, which still get preProcess and
      postProcess`: an application `WebMvcConfigurer` registers a recording `CallableProcessingInterceptor`; for an expired `Callable` it
      records `preProcess` then `postProcess`, and the library's check refuses the `Callable`; the library's configurer has order
      `Ordered.LOWEST_PRECEDENCE` (sits with the red refusal assertion, so not a guard);
    - `EXE-08 the check binds and restores nothing`: a counting `ScopeListener` sees the same number of scope entries with and without the
      library's interceptor registered for a `Callable` with time left (sits with the red assertion above in one test).
  - Green: `spring/DeadlineCallableInterceptor.java`, its `preProcess` check, and a `WebMvcConfigurer` bean ordered last in
    `MvcAsyncConfiguration` calling `registerCallableInterceptors` ([plan.md](plan.md#spring-wiring)); the Javadoc of
    `core/BoundExecutors.java` gains the sentence that the `Callable` check needs a `RequestMappingHandlerAdapter` built by
    `WebMvcConfigurationSupport`: TDD exception, reviewed ([plan.md](plan.md#tdd-exceptions)).
  - Completes: EXE-08 (MVC `Callable`); DL-08 (a `Callable`'s result).
  - Verify: Unit `MvcCallableTest`.

**Checkpoint**: IT-3.1 – IT-3.7 green on JDK 21 and JDK 25.

---

## Phase 6: Metrics

- [ ] T015 [US2] Count deadline expiries.
  - Red, in `springtest/DeadlineMetricsTest.java` (a `SimpleMeterRegistry` bean):
    - `DL-09 each expiry is counted once, where it is detected, tagged with its stage in lowercase`: one `inbound` (T006's request), one
      `before_send` (T008), one `timeout` (T010), one `executor` (T014's `Callable`), each counter at 1 under
      `boundrail.context.deadline.exceeded`;
    - `DL-09 the exception mapping counts only APPLICATION expiries`: a handler throwing `Deadline.check()`'s exception gives `application`
      1; a `BEFORE_SEND` expiry escaping a handler and mapped to 504 leaves only `before_send` at 1;
    - `DL-09 expiries detected by BoundExecutors are not counted`: an expired `BoundExecutors.attached` task in the context counts nothing,
      while the `Callable` case above counts (same test);
    - `DL-09 the counter uses the unique or primary MeterRegistry, never the global registry, and nothing fails without one`: two registries
      with one `@Primary` count in the primary; two without a primary, and none, count nowhere and the request still gets 504;
      `Metrics.globalRegistry` has no such counter.
  - Green: `spring/DeadlineCounter.java`, the Micrometer-backed one in `MicrometerConfiguration`, and the calls in the filter, the interceptor,
    the `Callable` check and the resolver ([plan.md](plan.md#spring-wiring)).
  - Completes: DL-09.
  - Verify: Unit `DeadlineMetricsTest`.

---

## Phase 7: Close

- [ ] T016 Verify each requirement one by one against the code and its tests, using the coverage table in
  [plan.md](plan.md#requirement-coverage):
  - Requirements: CORE-15, DL-03 – DL-09, DL-12, OUT-05 and EXE-08.
  - SC-001: DeadlinePropagationIT has no `@Disabled` left except IT-3.8 (`Pending: M6`); IT-3.1 – IT-3.7 pass in `integrationTestJdk21` and
    `integrationTestJdk25`.
  - SC-002: IT-3.2 and T010's unit tests.
  - SC-003: IT-3.4 and T006 (no expired request reaches a handler); IT-3.3 and T008 (no request is sent after expiry).
  - Every requirement has a test whose display name starts with its ID, except the Javadoc statements of the TDD exceptions: check the
    Javadoc of `BoundrailClients` against DL-07 and OUT-05, and of `BoundExecutors` against EXE-08, word for word in meaning.
  - The Configuration section's startup failures each have a test with lazy initialization (T003).
  - `boundrail-context-core` still has no runtime dependencies
    (`./gradlew :boundrail-context-core:dependencies --configuration runtimeClasspath`); Micrometer, Spring MVC and Boot's HTTP client stay
    `compileOnly` in `-spring` (`./gradlew :boundrail-context-spring:dependencies --configuration runtimeClasspath`).
  - By inspection: nothing new is static in core or `-spring` (RT-09); reflection is still limited to EXE-10's four fields; the `DeadlineTimer`
    scheduler is a bean of one context; the `Callable` check binds and restores nothing and is the only
    `CallableProcessingInterceptor` (G2); `DEADLINE` is still never written to the MDC (LOG-02).

  Run `./gradlew check` fresh, which covers JDK 21, JDK 25 and the forced ThreadLocal run. With the CI run green, this closes the feature.

## Dependencies & Execution Order

- T001 is done; nothing waits on it any more.
- Foundational: T002 is independent. T003 is independent of T002.
- US1: T004 needs T003. T005 needs T004. T006 needs T004. T007 needs T004 (the extra edge and the recording).
- US2: T008 needs T002 (it constructs `BEFORE_SEND` from `-spring`). T009 needs T002, T003 and T008 (IT-3.3 needs `BEFORE_SEND`). T010 needs
  T007 and T009 (IT-3.2's 504). T011 needs T007 and T008.
- US3: T012 needs T004 (edge's deadline). T013 needs T012 (same files). T014 needs T009.
- T015 needs T006, T008, T009, T010 and T014. T016 comes last.
- Tasks touching the same file run in sequence (`BoundrailContextAutoConfiguration`, `ContextInboundFilter`, `ContextClientInterceptor`,
  `EdgeApp`, `Topology` and `DeadlinePropagationIT` are touched by several).
- Every task is committed and reviewed on its own, on branch `005-deadlines`.

## Exit Checklist

- [x] T001's outcome recorded in plan.md, and G1 – G6 settled by the user
- [ ] Scenarios enabled and green on JDK 21 and JDK 25: IT-3.1 – IT-3.7
- [ ] Requirements implemented with tests named after their IDs (roadmap traceability row M4): CORE-15, DL-03 – DL-09, DL-12, EXE-08; the
  Javadoc statements of DL-07, OUT-05 and EXE-08 checked by inspection (TDD exceptions)
- [ ] Guard tests (T010 and T012) shown red against their temporary change, and the change reverted
- [ ] `boundrail-context-core` still has no runtime dependencies; Micrometer, Spring MVC and Boot's HTTP client are `compileOnly` in `-spring`
- [ ] `./gradlew check` green
