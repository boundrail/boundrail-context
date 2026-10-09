# Implementation Plan: Deadlines

**Branch**: `005-deadlines` | **Date**: 2026-10-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/005-deadlines/spec.md`, contract [contracts/deadline-api.md](contracts/deadline-api.md),
decisions D48 – D55 in [design.md](../../docs/design.md), the merged code of 002 – 004 (`main` at 1d31eda)

**Status**: Approved by the user (plan, tasks, both TDD exception rows and the one guard test) on 2026-10-09.
G2 – G6 are closed with the recommended answers, which the spec-creator writes into spec.md and design.md. G1 (how DL-07 caps the timeout)
stays open until T001 is done, and gates T010 ([Spec Gaps](#spec-gaps)).

## Summary

A request's time budget travels, shrinks and is enforced, and it stops at detached boundaries.

- `boundrail-context-core`:
  - public constructors of `DeadlineExceededException` (DL-12, CORE-15);
  - `BoundExecutors.attached` wrappers refuse to start a task whose captured deadline has expired: its future fails, or, for `execute`, the
    exception goes to the worker thread's `UncaughtExceptionHandler` (EXE-08).
- `boundrail-context-spring`, in the existing auto-configuration:
  - the `boundrail.context.deadline.*` properties, validated at startup, also under lazy initialization;
  - `ContextInboundFilter` binds the deadline from a trusted `Boundrail-Deadline-Ms` (capped) or `default-inbound`, in the scope it already
    enters, and answers an expired request with `expired-status` without running the chain (DL-03, DL-04);
  - `ContextClientInterceptor` fails fast on an expired deadline before anything else, writes the remaining milliseconds to internal calls,
    and caps the response timeout on the JDK `HttpClient` request factory (DL-05 – DL-07); a startup WARN when Boot's auto-configured
    factory is another one; the `BoundrailClients` Javadoc for DL-07 and OUT-05;
  - a lowest-precedence `HandlerExceptionResolver` mapping an escaping `DeadlineExceededException` to `expired-status` (DL-08);
  - an expired MVC `Callable` is not called, and its request completes with the exception (EXE-08, a check-only `preProcess`, G2);
  - the counter `boundrail.context.deadline.exceeded`, tagged by stage (DL-09).

Library code is driven by unit and `ApplicationContextRunner`/`MockMvc` tests. Each DeadlinePropagationIT scenario is enabled in the task whose
code makes it pass, so it is that task's failing acceptance test. One unit test is an approved guard (green before its task), and T010 adds
a second if G1 settles on option A; each is shown red against a named temporary change ([Guard Tests](#guard-tests)).

## Technical Context

**Language/Version**: Java 21 (`--release 21`); nothing new in core's `java25` source set (the expiry check sits in the executor wrappers,
above the slot)

**Primary Dependencies**:

- core: none (BLD-03).
- `-spring`: Spring Boot 4.1.1, Spring Framework 7.0.9. Possibly `compileOnly` on `spring-boot-http-client` for the factory check (T011),
  if it does not already reach the compile classpath through `spring-boot-restclient`; added in the green step of T011 with a catalog entry.
  `spring-webmvc`, `spring-web`, `micrometer-core`, `jakarta.servlet-api` are already `compileOnly`.
- `integration-tests`: nothing new. The JDK `HttpClient` factory is chosen on billing with Boot's `spring.http.clients.imperative.factory=jdk`.

**Storage**: N/A

**Testing**:

- core unit tests (`DeadlineExceededExceptionTest`, `BoundExecutorsTest`);
- `-spring` tests with `ApplicationContextRunner`, `WebApplicationContextRunner`, `MockMvc` with `asyncDispatch`, `support/RecordingServer`
  (extended with a delayed response in T010), `OutputCaptureExtension`, and the lazy-initialization setup first used in 003's T006a;
- DeadlinePropagationIT IT-3.1 – IT-3.7 on `integrationTestJdk21` and `integrationTestJdk25`; ContextPropagationIT and TrustBoundaryIT
  re-run in the tasks that change shared topology code.

**Target Platform**: JVM 21+, servlet-based Spring MVC, blocking `RestClient`/`RestTemplate`

**Project Type**: library (Spring Boot auto-configuration)

**Performance Goals**: no reflection and no extra allocation per request beyond the deadline arithmetic; the expiry check is one
`System.nanoTime()` comparison

**Constraints**:

- Monotonic clock only; the wire carries remaining milliseconds, rounded down (D50).
- Every adapter stays around-style for context (principle I). The one `CallableProcessingInterceptor.preProcess` (EXE-08, G2) only checks
  the deadline and binds and restores nothing; 004's ban on `preProcess`/`postProcess` as set/restore hooks for context still holds.
- EXE-10 lists the only private fields the Spring module may read. Nothing here reads another, unless the user changes EXE-10 under G1.
- No work from M5 – M6: no authority, no Kafka (IT-3.8 stays disabled).

**Scale/Scope**: 1 contract change; 12 requirements (CORE-15, DL-03 – DL-09, DL-12, OUT-05, EXE-08); 7 scenarios; a spike, 14 red-green
tasks (T002 – T015) and a close task (T016)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|---|---|---|
| I Scope-only | Pass | The deadline is bound with `Context.with` in the filter's existing scope (DL-03). The `Callable` check of EXE-08 in `preProcess` binds and restores nothing (G2) |
| II Declared propagation | Pass | `DEADLINE`'s built-in rules (POL-06) propagate it to `ATTACHED` and `INTERNAL_CALL` only; the header is written for `INTERNAL` destinations only (DL-05), WIRE-07 strips it elsewhere |
| III Secure by default | Pass | Only a trusted caller sets the deadline, capped by `max-inbound`; an untrusted header is reported, never bound (DL-03, IN-02) |
| IV Declared, never guessed | Pass | The deadline shrinks per hop (rounded down, D50), never travels to detached work (POL-04), and caps local timeouts (DL-07) and attached work (EXE-08) |
| V Evidence only | Pass | Each expiry is counted once, where it is detected, with its stage (DL-09); invalid headers are reported by key, never by value (DIAG-09) |
| VI Interoperate | Pass, pending G1 | Spring's resolver chain decides the response (DL-08); the capping mechanism of DL-07 depends on what T001 finds |
| VII Spec-first, test-first | Pass | Every task is one red-green cycle; both TDD exception rows and the guard test are approved |
| VIII Simplicity | Pass | One expiry check per adapter; no new public types in `-spring` (contract) |
| Technical constraints | Pass | Core stays dependency-free; Micrometer, Spring MVC and Boot's HTTP client stay `compileOnly` behind conditions |

## Spike (T001)

Not yet run. Reading the Spring Framework 7.0.9 and Spring Boot 4.1.1 sources (read-only, from the Gradle cache) already shows:

- **No per-request timeout hook.** `JdkClientHttpRequestFactory.createRequest` passes the factory's one `readTimeout` to the package-private
  `JdkClientHttpRequest`, which applies it with a `TimeoutHandler` that cancels the response future. `InterceptingClientHttpRequest`'s end of
  chain calls `requestFactory.createRequest(uri, method)` after the last interceptor and copies the request's attributes, which
  `JdkClientHttpRequest` ignores. So an interceptor cannot change the timeout of the request the factory creates. D51's premise, "the JDK
  client has a per-request timeout", holds for `java.net.http.HttpRequest.Builder.timeout`, but Spring does not expose it.
- **How a timeout surfaces.** The factory's timeout throws `java.net.http.HttpTimeoutException` (an `IOException`) out of
  `execution.execute(...)`, inside the interceptor; an interrupt of the calling thread cancels the response future and throws
  `IOException("Request was interrupted")`. `RestClient` and `RestTemplate` wrap an `IOException` in `ResourceAccessException` only after the
  interceptor; whether a `RuntimeException` thrown by the interceptor reaches the caller unwrapped must be confirmed (DL-08 maps only the
  exception itself).
- **Boot's factory.** `ImperativeHttpClientAutoConfiguration` defines a `ClientHttpRequestFactoryBuilder<?>` bean, chosen by
  `spring.http.clients.imperative.factory` or detected (Apache HttpComponents, Jetty, Reactor, JDK, simple, in that order) and adjusted by
  `ClientHttpRequestFactoryBuilderCustomizer` beans, whose `customize` must return the same builder type. The read timeout is
  `spring.http.clients.read-timeout`.

T001 answers, with throwaway code on JDK 21 and JDK 25:

1. **DL-07 mechanism (G1).** Which of these caps the effective timeout at `min(configured, remaining)`, with `TIMEOUT` only when the
   remaining time was the effective one:
   - **A. Interceptor timer.** The interceptor arms a one-shot timer for the remaining time around `execution.execute`, on a per-context
     scheduler bean; when it fires first, it interrupts the calling thread, the JDK request cancels its future, and the interceptor clears
     the interrupt and throws `DeadlineExceededException(TIMEOUT, cause)`. The client's own shorter timeout fires first and propagates
     unchanged. Needs no reflection and also covers clients given their own JDK factory in code. To check: the race between completion and
     the timer (a late interrupt must never leak), virtual threads, and what an interrupt does to the simple, Apache and Jetty factories
     (capping is only claimed for JDK; an interrupt there must not break the call).
   - **B. Replace the factory.** The library's client customizers rebuild Boot's factory and wrap it in one that, per request, creates a
     `JdkClientHttpRequestFactory` around the same `HttpClient` with `readTimeout = min(configured, remaining)`. `JdkClientHttpRequestFactory`
     has no getters, so this reads its private `httpClient`, `executor`, `readTimeout` and `compression` fields, which EXE-10 forbids, and a
     client given its own factory in code is not capped.
   - Record which phase each option covers: until the response headers arrive (inside the interceptor), or also while the body is read
     (after the interceptor returns; G4).
2. **Detecting Boot's factory (DL-07 WARN).** Whether `ClientHttpRequestFactoryBuilder<?>` bean `instanceof JdkClientHttpRequestFactoryBuilder`
   is reliable after Boot's customizers, without building a factory (which would create an `HttpClient`); what `detect` picks on the `-spring`
   test classpath and on the integration-test classpath; that `RestTemplateBuilder` uses the same builder bean.
3. **Exceptions through the clients.** A `DeadlineExceededException` thrown by the interceptor reaches the caller of
   `RestClient...retrieve().toBodilessEntity()` and of `RestTemplate.getForObject` unwrapped.
4. **EXE-08 `Callable` hook (G2).** A `CallableProcessingInterceptor` whose `preProcess` throws `DeadlineExceededException(EXECUTOR)` when the
   captured deadline (the snapshot `AttachedAsyncTaskExecutor` entered) has expired: `WebAsyncManager` then skips the `Callable`, sets the
   exception as the concurrent result and dispatches, also for a `WebAsyncTask` without its own executor. Confirm the registration G2
   settled: a `WebMvcConfigurer` (`configureAsyncSupport` → `registerCallableInterceptors`) keeps the application's own interceptors.
5. **DL-08 at edge.** A lowest-precedence `HandlerExceptionResolver` calling `sendError(504)` runs after `@ExceptionHandler` methods, and the
   resulting `ERROR` dispatch through Spring Security on edge (bearer JWT, stateless) still answers 504, not 401.
6. **Blank durations.** Whether Boot binds `deadline.default-inbound=` and `deadline.default-inbound=` followed by spaces to `null`; if not, the
   property is bound as a `String` and converted with `ApplicationConversionService`, as T001 of 004 found for `executors.modes`.

No code is kept. The outcome is recorded here, and any spec change it needs goes to the user before T010 or T014 starts.

## Research Notes

### Core

- **Constructors (DL-12, CORE-15).** `DeadlineExceededException(Stage)` and `(Stage, Throwable)` become public; both call
  `Objects.requireNonNull` on each argument before `super(...)` (a helper that returns the message), so a `null` stage no longer yields the
  message `Deadline exceeded at stage null`. `Deadline.check()` keeps calling the first.
- **Refusing expired tasks (EXE-08).** In `BoundExecutorService` and `BoundExecutor`, the task is wrapped as today (`snapshot.wrap(task)`),
  plus a check that runs first on the worker thread: if the captured snapshot's `DEADLINE` is bound and expired, the task is not called.
  - `submit` and `invokeAll`/`invokeAny`: the wrapped `Callable` or `Runnable` throws `DeadlineExceededException(EXECUTOR)`, so the delegate's
    `FutureTask` completes exceptionally and `get` throws `ExecutionException` with it as the cause.
  - `execute`: the wrapped `Runnable` passes the exception to `Thread.currentThread().getUncaughtExceptionHandler().uncaughtException(...)` and
    returns normally, so a pool thread survives and an inline executor behaves the same (D55).
  - A `DETACHED` snapshot never holds `DEADLINE` (built-in rules, POL-04, POL-06), so one check serves both modes.
  - Expiries detected here are not counted (DL-09, D54).
- **No static state.** Nothing new is static in core (RT-09).

### Spring wiring

- **Properties.** `BoundrailContextProperties` gains `@DefaultValue Deadline deadline`, a record `(Duration defaultInbound, @DefaultValue("30s")
  Duration maxInbound, @DefaultValue("504") int expiredStatus, @DefaultValue("true") boolean mapException)`. The compact constructor rejects a
  `defaultInbound` or `maxInbound` that is zero or negative, and an `expiredStatus` outside 400 – 599, with `IllegalArgumentException` naming
  the property. A blank `default-inbound` is `null` if T001 confirms Boot's converter returns `null` for it; otherwise the raw string is bound
  (T001 item 6). Boot's binder rejects a non-duration, a non-integer status and a non-boolean `map-exception`. The `@Lazy(false)` reporter
  already binds the properties at startup, so every failure also occurs under lazy initialization (T003 uses 003's T006a setup).
- **Inbound (DL-03, DL-04).** In `ContextInboundFilter.doFilterInternal`, on the initial dispatch, after the IN-02 keys:
  1. a trusted request: read `Boundrail-Deadline-Ms` with the same steps as `bind` (several lines → one `INVALID_VALUE`; longer than
     `max-value-length` → one `OVERSIZED_VALUE`), then a strict ASCII-digit check (`'0'`–`'9'`, at least one). A valid value of more than 18
     digits, or one above `max-inbound` in milliseconds, is `max-inbound`; otherwise `Duration.ofMillis(value)`, then `min` with `max-inbound`.
     An invalid value gives one `INVALID_VALUE` for `boundrail.deadline`; then `default-inbound` applies;
  2. no header, or an untrusted request (already reported by `reportUntrusted`): `default-inbound` if set;
  3. `Deadline.after(budget)` joins the same `ContextBinding` as the IN-02 keys, so `SNAPSHOT_ATTRIBUTE` holds it (IN-09);
  4. DL-04: if that deadline `isExpired()`, `response.setStatus(expiredStatus)`, nothing written, no `sendError`, and the chain is not
     called. The `ASYNC` branch (`reenter`) is unchanged, so it never checks again.

  The filter receives the deadline settings through its constructor. The deadline is bound in the filter's scope, outside Spring Security, so
  the edge's 500ms default also covers authentication.
- **Outbound (DL-05 – DL-07).** `ContextClientInterceptor.intercept` becomes, in order:
  1. DL-06: `Deadlines.current()` expired → `DeadlineExceededException(BEFORE_SEND)`, before `checkRequiredKeys`, so no violation and no
     header, for every destination;
  2. the required-key check and header writing as today;
  3. DL-05, for `INTERNAL_CALL` with a deadline bound: `remaining = deadline.remaining()` read last, just before `execute`, written as
     `remaining.toMillis()` (rounded down, through a package-private static `headerValue(Duration)` that unit tests pin); an application's
     single valid value (DL-03's digit rule) that is smaller wins; WIRE-04 applies to the value written (when the value is oversized, the
     application's header is removed too and one `OVERSIZED_VALUE` is reported, G3). With no deadline bound, an application's header
     stays (OUT-04). For external and unknown calls WIRE-07 already strips the header;
  4. DL-07, with a deadline bound: the mechanism T001 and G1 settle; a `TIMEOUT` expiry throws `DeadlineExceededException(TIMEOUT, cause)`
     with the client's exception as the cause.
- **Factory check (DL-07).** A `@Lazy(false)` bean in `ClientConfiguration` reads the `ClientHttpRequestFactoryBuilder<?>` bean, if any, once
  at startup, and logs one WARN through `LogFactory` when it is not the JDK one (T001 item 2), naming the property to change. No WARN when no
  such bean exists (no Boot HTTP client auto-configuration).
- **Exception mapping (DL-08).** In a nested configuration conditional on a servlet web application and on `HandlerExceptionResolver`, and
  on `map-exception` (read from the bound properties, not `@ConditionalOnProperty`, so `yes` and `on` work, as T015a of 004 found), a
  package-private `DeadlineExceptionResolver` with `Ordered.LOWEST_PRECEDENCE`. `DispatcherServlet` sorts it after Boot's
  `HandlerExceptionResolverComposite` (order 0), which holds the `@ExceptionHandler` resolver. It handles only `ex instanceof
  DeadlineExceededException` and an uncommitted response, calls `response.sendError(expiredStatus)` and returns an empty `ModelAndView`;
  otherwise it returns `null`. A `Callable` whose result is the exception reaches it on the async dispatch.
- **`Callable` expiry (EXE-08, G2).** A check-only `CallableProcessingInterceptor` whose `preProcess` throws
  `DeadlineExceededException(EXECUTOR)` when `Deadlines.current()` is expired. It runs on MVC's executor thread inside the snapshot
  `AttachedAsyncTaskExecutor` entered, so it sees the captured deadline; it binds and restores nothing. It is registered by a `WebMvcConfigurer`
  bean in the existing `MvcAsyncConfiguration` (`configureAsyncSupport` → `registerCallableInterceptors`), which adds to the application's
  own interceptors. A `RequestMappingHandlerAdapter` not built by `WebMvcConfigurationSupport` does not get it; the Javadoc of
  `BoundExecutors` says so, next to EXE-08's decorated-bean statement (T014).
- **Metric (DL-09).** A package-private functional interface `DeadlineCounter { void increment(Stage stage); }`, as `ViolationReporter.Counter`
  is: the Micrometer configuration supplies one that resolves `ObjectProvider<MeterRegistry>.getIfUnique()` (unique or primary) and
  increments `boundrail.context.deadline.exceeded` with `stage=<lowercase>`; without Micrometer, a no-op. The filter (`inbound`), the
  interceptor (`before_send`, `timeout`), the `Callable` check (`executor`) and the resolver (`application` only) call it where they detect
  the expiry. Never `Metrics.globalRegistry` (RT-09).
- **RT-09.** Nothing new is static in `-spring`; a scheduler for option A of G1 would be a bean of one context, shut down with it.

### Integration tests

- **Edge for IT-3.x (G5).** DeadlinePropagationIT starts one extra edge in `@BeforeAll` with `Topology.Running.startEdge(
  "boundrail.context.deadline.default-inbound=500ms", "boundrail.context.executors.modes.attachedExecutor=attached")` and closes it in
  `@AfterAll`, as 001's topology prescribes for a scenario needing other configuration. The shared edge gets no default, so IT-1 and IT-2
  requests carry no deadline and are not cut off by a cold JVM. Orders and billing are the shared instances (spec: no default).
- **Deadline recording.** `ObservedContext.ping` adds `ctx.deadline-remaining-nanos` = `Deadlines.current().map(d ->
  d.remaining().toNanos())` when bound. It is the first statement of every handler that records, so it is "on arrival". Nanoseconds keep
  `edge > orders > billing` strict; milliseconds could tie.
- **Warm-up.** `@BeforeAll` sends one unasserted request through each path a scenario times (the IT-3.1 chain and the IT-3.6 request), so
  class loading and the first JWT verification do not eat the 500ms budget.
- **IT-3.6 robustness.** The scenario distinguishes the 500ms default from the ignored 5ms header. After warm-up, edge's filter, Spring
  Security and dispatch take a few milliseconds, so "more than 5ms" has about 490ms of margin, and the upper bound (`≤ 500ms`) is exact by
  construction. The edge records in nanoseconds at handler entry; the test asserts `5_000_000 < remaining ≤ 500_000_000`, the 200 status and
  a delta of one `UNTRUSTED_HEADER` for `boundrail.deadline` on the extra edge's own `MeterRegistry`. No assertion depends on wall-clock
  sleeps.
- **Timing elsewhere.** IT-3.2 measures elapsed time inside billing (remaining before the call, `nanoTime` before and after), so the bound
  "remaining when sent plus 200ms" excludes the test client's own latency. IT-3.3 and IT-3.7 wait until `Deadlines.current()` reports
  expired (sleep `remaining + 20ms`), not a fixed time.
- **Topology additions (test code, each in the first task that needs it).**
  - T004: `ObservedContext` deadline entry; DeadlinePropagationIT's extra edge, warm-up, and its trusted client (`RestClient.builder()` with
    `TestInboundTrustVerifier.peer()`, as TrustBoundaryIT does).
  - T007: `Topology` registers the `topology` singleton for orders and billing too (they resolve URIs per request); orders gets an internal
    billing client (`BoundrailClients.internal("billing")`, `peer()`) and `/call-billing`, which records on arrival and pings billing; edge
    gets `/deadline/chain`, which records and calls orders' `/call-billing`.
  - T009: edge `/deadline/sleep-then-call-orders`: records, sleeps past its deadline, calls orders; on `DeadlineExceededException` it records
    the stage under `edge-deadline` and rethrows.
  - T010: partner `/slow`: records its headers, then sleeps 5s; billing `/call-partner/slow` with an `external()` client from its
    auto-configured builder; billing started with `spring.http.clients.imperative.factory=jdk` and `spring.http.clients.read-timeout=10s`;
    billing records the remaining time before the call and the elapsed time and stage after it, under `billing-partner-call`.
  - T012: edge beans `attachedExecutor` (`ThreadPoolTaskExecutor`, one thread, prefix `attachedExecutor-`) and `boundAttachedExecutor`
    (`BoundExecutors.attached(` a one-thread `ExecutorService`, `TopologyPolicy.policy())`), and `/deadline/executors`.
- **Shared JVM.** Observations and counters are shared; each scenario uses a marker and asserts deltas on its own edge.

### Verify commands

Unit: `./gradlew :<project>:test --tests '<Class>'`. Scenarios (Gradle applies `--tests` to the task just before it):

```bash
./gradlew --continue :integration-tests:integrationTestJdk21 --tests '*DeadlinePropagationIT' \
          :integration-tests:integrationTestJdk25 --tests '*DeadlinePropagationIT'
```

Tasks that change shared topology code (T007, T010, T012) also run `*ContextPropagationIT` and `*TrustBoundaryIT` the same way. Every task
ends with `./gradlew check`.

## Spec Gaps

G2 – G6 are closed by the user's decision (the recommended answers), which the spec-creator writes into spec.md and design.md; their tests are
in the tasks named. G1 stays open until T001 is done.

| Gap | Status | Decision | Tasks |
|---|---|---|---|
| G1 | **Open; gates T010** | Spring 7.0.9 offers no per-request timeout on the JDK factory, so DL-07 needs option A (an interceptor timer that interrupts the calling thread) or option B (replacing Boot's factory, reading four private fields of `JdkClientHttpRequestFactory`, which EXE-10 forbids). *Recommendation*: A, if T001 shows the interrupt race can be closed and the other factories survive an interrupt; B would need EXE-10 amended and DL-07 to say that a client given its own JDK factory is not capped | T001, T010 |
| G2 | Closed | EXE-08's `Callable` check is a check-only `CallableProcessingInterceptor.preProcess` that binds and restores nothing, registered through a `WebMvcConfigurer` (`registerCallableInterceptors`); a `RequestMappingHandlerAdapter` not built by `WebMvcConfigurationSupport` does not get it, which the `BoundExecutors` Javadoc states | T014 |
| G3 | Closed | When the value DL-05 would write is longer than `wire.max-value-length`, an application-set `Boundrail-Deadline-Ms` is removed too, and one `OVERSIZED_VALUE` is reported, so a child never receives a longer budget (§4.8) | T007 |
| G4 | Closed | DL-07 covers the call until the response headers arrive; reading the body keeps the client's configured timeout, and a timeout there propagates as the client's own exception | T010 |
| G5 | Closed | DeadlinePropagationIT starts its own extra edge with `deadline.default-inbound=500ms` (and `executors.modes.attachedExecutor=attached`), as 001's Test Topology allows; the shared edge has no default | T004, T007, T009, T012 |
| G6 | Closed | A trusted `Boundrail-Deadline-Ms` on several lines is one `INVALID_VALUE`, with no length check, as IN-02 does for every key, also when a line is oversized | T005 |

## Guard Tests

These tests already pass before the task that introduces them. As in 002 – 004, each red is shown against a named temporary change, which
is reverted before the commit. They are not TDD exceptions, but the user is asked to accept them with the plan.

A test that is green before its task is accepted as a guard when its red is shown against a named temporary change to the finished code, which
is then reverted; the leader reports it in the task report, the planner adds it to this table, and the reviewer checks it.

A negative assertion that sits in the same test as a positive assertion that is red before its task is not a guard, and is not listed (for
example, "no `MISSING_REQUIRED` is reported" sits with "`BEFORE_SEND` is thrown" in T008, and "with `map-exception=false` it is not mapped"
with "with `true` it is" in T009).

| Task | Scenario or test | Why green first | Red shown against (temporary, reverted) |
|---|---|---|---|
| T012 | `EXE-08 a decorated executor in attached mode runs a task whose deadline has expired, and the task sees the deadline` | `ExecutorDecorator` has never checked deadlines; EXE-08 keeps it so (D55) | `ExecutorDecorator` throwing `DeadlineExceededException(EXECUTOR)` from the wrapped task when the captured deadline has expired |
| T010 (only if G1 settles on option A; not yet approved) | `DL-07 a call that completes within the remaining time is not affected` | Before T010 nothing caps or interrupts | The interceptor's timer left armed after `execute` returns, so it interrupts the thread later |

## TDD Exceptions

Both rows are approved. T014 adds one sentence to the second row's Javadoc (G2); see the note under the table.

| Task | What | Verified by |
|---|---|---|
| T011 | The Javadoc statements of `BoundrailClients`: DL-07 (timeout capping needs the JDK `HttpClient` request factory; a client given its own request factory in code is not checked) and OUT-05 (a followed 3xx redirect is not classified again and may carry the original destination's context headers; turn off redirect following on internal clients and on clients reaching internal host patterns). Documentation, with no behaviour a test can fail on | Review of T011, and inspection in T016 against each requirement's wording |
| T013 | The Javadoc statement of `BoundExecutors` required by EXE-08: executor beans the Spring module decorates in `ATTACHED` mode carry the deadline but run the task whether or not it has expired. The behaviour itself is tested (the T012 guard) | Review of T013, and inspection in T016 |

Note for the user: G2's decision adds a Javadoc-only statement of the same kind, that the `Callable` check needs an adapter built by
`WebMvcConfigurationSupport` (T014). It is not covered by the approval above until the user confirms it as part of the T013 row.

T001 is a spike, not an exception: no code from it is kept. A dependency or catalog entry, if T011 needs one, is part of a green step.

## Project Structure

### Documentation (this feature)

```text
specs/005-deadlines/
├── spec.md
├── plan.md                # this file
├── tasks.md
└── contracts/deadline-api.md
```

### Source Code (repository root)

```text
boundrail-context-core/src/main/java/io/github/boundrail/context/
├── DeadlineExceededException.java (public constructors)            # T002
├── BoundExecutorService.java  BoundExecutor.java (expiry check)     # T012, T013
└── BoundExecutors.java (Javadoc, EXE-08)                            # T013
boundrail-context-core/src/test/java/io/github/boundrail/context/
├── DeadlineExceededExceptionTest.java                              # T002
└── BoundExecutorsTest.java                                         # T012, T013
boundrail-context-spring/build.gradle.kts                           # T011, if needed
boundrail-context-spring/src/main/java/io/github/boundrail/context/spring/
├── BoundrailContextProperties.java (Deadline)                      # T003
├── BoundrailContextAutoConfiguration.java                          # T003 – T015
├── ContextInboundFilter.java (DL-03, DL-04)                        # T004 – T006
├── ContextClientInterceptor.java (DL-05 – DL-07)                   # T007, T008, T010
├── <timeout mechanism per G1, package-private>                     # T010
├── RequestFactoryCheck.java (package-private)                      # T011
├── BoundrailClients.java (Javadoc)                                 # T011
├── DeadlineExceptionResolver.java (package-private)                # T009
├── DeadlineCallableInterceptor.java (package-private, per G2)      # T014
└── DeadlineCounter.java (package-private)                          # T015
boundrail-context-spring/src/test/java/io/github/boundrail/context/spring/
├── DeadlinePropertiesTest.java  InboundDeadlineTest.java  OutboundDeadlineTest.java  DeadlineTimeoutTest.java
├── RequestFactoryCheckTest.java  DeadlineExceptionMappingTest.java  DeadlineMetricsTest.java
├── ContextInboundFilterTest.java  ContextClientInterceptorTest.java (two tests narrowed)  ExecutorModeTest.java  MvcCallableTest.java
└── support/RecordingServer.java (delayed response)                 # T010
integration-tests/src/test/java/io/github/boundrail/context/it/
├── DeadlinePropagationIT.java (IT-3.1 – IT-3.7)
└── topology/ObservedContext.java  Topology.java  EdgeApp.java  OrdersApp.java  BillingApp.java  PartnerApp.java
```

**Structure Decision**: The contract's constructors are the only public change. Everything new in `-spring` is package-private, as in 003 and
004.

## Requirement Coverage

| Requirement or scenario | Tasks |
|---|---|
| CORE-15 | T002 |
| DL-12 | T002 |
| DL-03 | T003 (`default-inbound`, `max-inbound` binding and validation), T004 (trusted value, cap, default, untrusted, scope), T005 (invalid, repeated, oversized values) |
| DL-04 | T003 (`expired-status`), T006 |
| DL-05 | T007 |
| DL-06 | T008 |
| DL-07 | T010 (capping, `TIMEOUT`), T011 (startup WARN, Javadoc: TDD exception) |
| OUT-05 | T011 (Javadoc: TDD exception) |
| DL-08 | T003 (`map-exception`), T009, T014 (`Callable` result mapped) |
| DL-09 | T015 |
| EXE-08 | T012 (`submit`, `invokeAll`, `invokeAny`; decorated executors run expired tasks: guard), T013 (`execute`; Javadoc: TDD exception), T014 (MVC `Callable`) |
| IT-3.1 | T007 |
| IT-3.2 | T010 |
| IT-3.3 | T009 |
| IT-3.4 | T006 |
| IT-3.5 | T004 |
| IT-3.6 | T004 |
| IT-3.7 | T012 |
| SC-001 – SC-003 | T016 |

## Risks

- **Timeout capping (DL-07, G1).** Spring exposes no per-request timeout; T001 decides between an interrupt-based timer and factory
  replacement. Either way IT-3.2 and T010's unit tests pin the behaviour, not the mechanism.
- **Cold-JVM timing.** A 500ms budget across three services is tight on a cold JVM; the extra edge, warm-up requests and nanosecond
  recordings (Integration tests) keep IT-3.1 and IT-3.6 stable. If they still flake, the fix is in the test harness, not a looser assertion.
- **Spring Security error dispatch (DL-08 at edge).** `sendError` makes an `ERROR` dispatch through Security; T001 item 5 checks that IT-3.3
  still gets 504.
- **Interrupts (option A of G1).** A late timer interrupt must never reach application code; T010's tests include a call that completes just
  before the budget.
- **Existing tests that M4 contradicts.** `IN-02 a trusted request binds no deadline and no authority from headers` and `IN-02 a repeated
  Boundrail-Deadline-Ms on a trusted request is skipped, not reported` (`ContextInboundFilterTest`), and `OUT-04 an internal call writes no
  Authorization and no Boundrail-Deadline-Ms` (`ContextClientInterceptorTest`) encode M2's "not before M4". T004, T005 and T007 narrow them
  to their M2 part, and the deadline part moves into those tasks' red tests.
- **Shared topology.** New edge executor beans appear in IT-1.3 – IT-1.5's audit output as covered, not as gaps; T012 re-runs
  ContextPropagationIT.

## Complexity Tracking

No constitution violations to justify. G1 option B would need a spec change (EXE-10) before T010; it is listed as a gap, not taken.
