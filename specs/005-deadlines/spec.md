# Feature Specification: Deadlines

**Feature Branch**: `005-deadlines`

**Created**: 2026-09-28

**Status**: Ready for planning

**Milestone**: M4

**Input**: Roadmap milestone [M4](../../docs/roadmap.md#m4--deadlines).

API signatures: `Deadline`, `Deadlines` and `DeadlineExceededException` are defined in
[002-core-runtime](../002-core-runtime/contracts/core-api.md); this feature adds public constructors to `DeadlineExceededException`
([contracts/deadline-api.md](contracts/deadline-api.md)). Test topology: [001-foundation](../001-foundation/spec.md#test-topology).

## User Scenarios & Testing *(mandatory)*

In DeadlinePropagationIT, "edge" is an extra edge instance that the suite starts with `Topology.Running.startEdge(...)` and the property
`boundrail.context.deadline.default-inbound=500ms`; the shared edge, orders and billing have no default (DESIGN D57). Every service records
`Deadlines.current()`, as remaining time, to `ObservedContext` when a request arrives at its handler. A "trusted caller" is the test sending
the request with `TestInboundTrustVerifier.peer()` (TEST-01).

### User Story 1 - The time budget travels and shrinks (Priority: P1)

A request's remaining time budget travels to internal services as remaining milliseconds, and each service computes its own deadline on arrival.
Only trusted callers can set it, capped by a maximum; the edge can apply a default.

**Why this priority**: Without it, downstream services keep working long after the original caller gave up.

**Independent Test**: DeadlinePropagationIT IT-3.1, IT-3.5 and IT-3.6.

**Acceptance Scenarios** (DeadlinePropagationIT):

1. **IT-3.1 Budget shrinks across hops.** Given a public request authenticated as alice, when edge calls orders through an internal client and
   orders calls billing through an internal client, then the remaining budgets that edge, orders and billing record on arrival satisfy
   `500ms ≥ edge > orders > billing > 0`. *(DL-03, DL-05)*
2. **IT-3.5 Inbound cap.** When a trusted caller sends `Boundrail-Deadline-Ms: 600000` to orders, then orders records a deadline with at most 30s
   remaining. *(DL-03)*
3. **IT-3.6 Untrusted deadline ignored.** When a public caller sends `Boundrail-Deadline-Ms: 5` to edge, then edge reports an `UNTRUSTED_HEADER`
   violation for it, records a deadline with more than 5ms and at most 500ms remaining (the default), and the request succeeds. *(DL-03, IN-02)*

---

### User Story 2 - Expired work fails fast (Priority: P1)

Outbound calls use the smaller of their configured timeout and the remaining budget. A call attempted after expiry is never sent; a request that
arrives expired is rejected without running the handler.

**Why this priority**: A declared timeout means nothing if the caller's budget is already gone.

**Independent Test**: DeadlinePropagationIT IT-3.2 – IT-3.4.

**Acceptance Scenarios** (DeadlinePropagationIT):

1. **IT-3.2 Slow external call fails fast.** Given a billing client for partner, declared `external()` and built on the JDK `HttpClient` request
   factory with a configured read timeout of 10s, when a trusted caller sends `Boundrail-Deadline-Ms: 500` to a billing handler that calls
   partner's endpoint that sleeps for 5s, then the call fails with `DeadlineExceededException` (stage `TIMEOUT`) no later than the budget
   remaining when it was sent plus 200ms, partner receives no `Boundrail-Deadline-Ms` header, and billing responds with 504.
   *(DL-05, DL-07, DL-08, OUT-04)*
2. **IT-3.3 Expired before send.** When an edge handler serving a request authenticated as alice sleeps past its deadline and then calls orders, then
   `DeadlineExceededException` (stage `BEFORE_SEND`) is thrown, orders receives no request, and edge responds with 504. *(DL-06, DL-08)*
3. **IT-3.4 Expired on arrival.** When a trusted caller sends `Boundrail-Deadline-Ms: 0` to orders, then orders responds with 504 and its handler is
   not invoked. *(DL-04)*

---

### User Story 3 - Deadlines stop at detached boundaries (Priority: P2)

Attached executors carry the deadline, and those that wrap the task's future refuse to start work whose deadline has passed; detached executors do
not receive the deadline at all.

**Why this priority**: Fire-and-forget work must not be dropped because the request that started it ran out of time.

**Independent Test**: DeadlinePropagationIT IT-3.7.

**Acceptance Scenarios** (DeadlinePropagationIT):

1. **IT-3.7 Attached versus detached executors.** Within a public request authenticated as alice (tenant `acme`), which has edge's 500ms default
   deadline:
   - a task on `attachedExecutor`, an edge `ThreadPoolTaskExecutor` bean configured with `executors.modes.attachedExecutor=attached`, observes the
     deadline;
   - a task on `taskExecutor` (detached) observes no deadline but does observe `tenant=acme`;
   - a task submitted to `boundAttachedExecutor`, an edge bean declared as `BoundExecutors.attached(...)` around a single-thread
     `ExecutorService`, while that thread is blocked until the deadline has expired, is not run, and its future's `get` throws
     `ExecutionException` whose cause is `DeadlineExceededException` (stage `EXECUTOR`).

   *(EXE-01, EXE-02, EXE-04, EXE-08, POL-04)*

---

### Edge Cases

- A request factory other than the JDK `HttpClient` one: DL-07 (one WARN at startup that capping is not guaranteed; the timer is still armed,
  and the header and fail-fast still apply). The Simple or Apache HttpComponents factory on platform threads: DL-07 (not capped).
- A client given its own request factory in code, a customizer that wraps Boot's `ClientHttpRequestFactoryBuilder`, or an application builder
  for the JDK factory: DL-07 (the startup WARN can be missing or wrong; the `BoundrailClients` Javadoc says so).
- The response headers arrive as the remaining time runs out: DL-07 (the response is returned; no interrupt is left set).
- An application interrupt arrives as the timer fires: DL-07 (cleared too; a stated limitation). The thread is already interrupted before the
  call: DL-07 (not cleared; the JDK client fails at once and its exception is rethrown unchanged).
- A client with no configured read timeout: DL-07 (the remaining budget is the timeout).
- A client whose configured timeout is shorter than the remaining budget and fires first: DL-07 (the client's own exception, unchanged).
- The remaining budget is under 1ms but not yet expired when a call is sent: DL-05 (`Boundrail-Deadline-Ms: 0` is sent, and the receiver
  responds with `expired-status`, DL-04).
- A deadline bound in a scope whose snapshot is later re-entered on an MVC `ASYNC` dispatch (IN-09): the stored deadline is kept and not
  checked again by DL-04; an expiry surfaces as `DeadlineExceededException` (DL-06, DL-07, EXE-08), mapped by DL-08.
- Application code sets `Boundrail-Deadline-Ms` on an external or unknown call: WIRE-07 (removed, one `FORBIDDEN_HEADER`). On an internal
  call while a deadline is bound: DL-05 (the smaller of its valid value and the remaining time is sent); with no deadline bound: OUT-04
  (unchanged).
- Both an expired deadline and an unbound required key on one call: DL-06 (`BEFORE_SEND` is thrown; no `MISSING_REQUIRED` is reported).
- `wire.max-value-length` is set below the length of an inbound `Boundrail-Deadline-Ms` value: WIRE-04, DL-03 (not bound; one
  `OVERSIZED_VALUE`; `default-inbound` applies). A repeated header with one line too long: DL-03 (one `INVALID_VALUE`, no length check).
- `Context.without(DEADLINE)`: DL-11 (rejected); work that must outlive the request uses a detached executor (EXE-02).
- An untrusted request with `Boundrail-Deadline-Ms` and no `default-inbound`: DL-03, IN-02 (no deadline is bound; one `UNTRUSTED_HEADER`).
- A trusted `Boundrail-Deadline-Ms` such as `abc`, `-1`, `+5`, `1.5` or empty, or carried on more than one header line: DL-03 (unbound, one
  `INVALID_VALUE`, and `default-inbound` applies if set). A value of digits too large for a `long`: DL-03 (valid, capped by `max-inbound`).
- An application `@ExceptionHandler` for `DeadlineExceededException`: DL-08 (the application's handler wins).
- A `DeadlineExceededException` wrapped in another exception escapes a handler: DL-08 (not mapped).
- A `RequestMappingHandlerAdapter` not built by `WebMvcConfigurationSupport`: EXE-08 (its `Callable`s are not refused after expiry; documented).
- The remaining-milliseconds value to write is longer than `wire.max-value-length`: DL-05 (the header, even one application code set, is
  removed; one `OVERSIZED_VALUE`).
- A timeout while reading the response body: DL-07 (the client's own exception, unchanged).
- A decorated executor bean in `attached` mode whose task starts after its deadline: EXE-08 (the task runs; the `BoundExecutors` Javadoc says so).
- An HTTP client that follows a 3xx redirect: OUT-05 (a documented v0.1 limitation; DESIGN D52).
- Invalid configuration with `spring.main.lazy-initialization=true`: an invalid deadline property still fails startup (Configuration).
- A `null` argument passed to the M4 contract: CORE-15.
- Network transit time is not subtracted in v0.1 (known limitation, DESIGN §7).

## Requirements *(mandatory)*

### API conventions

**CORE-15** `[M4]` CORE-03 SHALL apply to the constructors declared in [contracts/deadline-api.md](contracts/deadline-api.md) as it does to
`contracts/core-api.md`: a `null` stage or cause SHALL throw `NullPointerException` at that call. Verified by unit tests.

### Deadline exceptions

**DL-12** `[M4]` `DeadlineExceededException` SHALL have the public constructors `(Stage stage)` and `(Stage stage, Throwable cause)` declared in
[contracts/deadline-api.md](contracts/deadline-api.md). Both SHALL set the message `Deadline exceeded at stage <STAGE>`, with the stage's
constant name, and `stage()` SHALL return the given stage; the second SHALL also set the given cause, returned by `getCause()` (DESIGN D48).
Verified by unit tests.

### Inbound HTTP

**DL-03** `[M4]` WHEN a trusted inbound request carries `Boundrail-Deadline-Ms`, `ContextInboundFilter` (IN-01) SHALL bind
`Deadline.after(min(value, max-inbound))`. WHEN the header is absent or the caller is untrusted, and `boundrail.context.deadline.default-inbound` is
set, the filter SHALL bind `Deadline.after(default-inbound)`. Otherwise no deadline is bound. The deadline SHALL be bound in the same scope as the
keys IN-02 binds, so the snapshot stored for `ASYNC` dispatches (IN-09) holds it. An untrusted `Boundrail-Deadline-Ms` is reported as IN-02
defines. A trusted value SHALL be valid when it is one or more ASCII digits (`0` – `9`) and nothing else; a valid value too large for a `long`
SHALL be accepted and capped by `max-inbound`. WIRE-04 SHALL apply to the value first. IF a trusted value is not valid, or the header is carried
on more than one header line (as IN-02 defines; no line is then length-checked, even one that is too long, DESIGN D57), THEN the filter SHALL
bind no deadline from it, SHALL report one `INVALID_VALUE` violation for `DEADLINE` (DIAG-08, DIAG-09), and SHALL then apply
`default-inbound` as if the header were absent; an oversized value is reported as
`OVERSIZED_VALUE` (WIRE-04) and treated the same way. `default-inbound` and `max-inbound` SHALL be bound as `Duration`s with Spring Boot's
binding, and SHALL be greater than zero; a blank `default-inbound` SHALL count as not set, and a blank `max-inbound` SHALL mean its
default, 30s. `default-inbound` MAY be greater than `max-inbound`,
which caps trusted headers only. IF either is zero, negative or cannot be converted, THEN application startup SHALL fail, even when
`spring.main.lazy-initialization` is `true` (DESIGN D49). Verified by IT-3.1, IT-3.5, IT-3.6 and unit tests.

**DL-04** `[M4]` IF the deadline bound at inbound (DL-03) is already expired, THEN the filter SHALL set the response status to
`boundrail.context.deadline.expired-status` (default 504), with an empty body and without calling `sendError`, and SHALL NOT invoke the rest of
the filter chain (DESIGN D53). The check SHALL be made once, on the request's initial dispatch; an `ASYNC` dispatch is not checked again (IN-09).
`expired-status` SHALL be from 400 to 599; IF it is outside that range or cannot be converted to an integer, THEN application startup SHALL
fail, even when `spring.main.lazy-initialization` is `true`. Verified by IT-3.4 and unit tests.

### Outbound HTTP

**DL-05** `[M4]` WHEN the library's interceptor (OUT-01) sends a request to an `INTERNAL` destination with a deadline bound, it SHALL set
`Boundrail-Deadline-Ms` to the remaining milliseconds, computed immediately before sending and rounded down to a whole millisecond, so a
receiver's budget is never longer than the sender's (DESIGN §4.8). IF application code has already set `Boundrail-Deadline-Ms` on that request,
THEN, as an exception to OUT-04, the interceptor SHALL replace it with the smaller of the application's value and the remaining milliseconds when
the application's value is one header value that is valid as DL-03 defines, and with the remaining milliseconds otherwise. With no deadline
bound, an application's header is left unchanged (OUT-04). IF the value to write is longer than `wire.max-value-length` (WIRE-04), THEN the
interceptor SHALL remove `Boundrail-Deadline-Ms` from the request, including a header application code set, and SHALL report one
`OVERSIZED_VALUE` violation, so a receiver never gets a longer budget than the sender's (DESIGN D50, D57). Verified by IT-3.1, IT-3.2 and unit
tests.

**DL-06** `[M4]` IF the deadline is expired when the library's interceptor is about to send a request to any destination, THEN it SHALL throw
`DeadlineExceededException` (stage `BEFORE_SEND`) and SHALL NOT send the request. This check SHALL come first, before the required-key check
(POL-08), so no violation is reported and no header is written for that request (DESIGN D50). Verified by IT-3.3 and unit tests.

**DL-07** `[M4]` WHEN the library's interceptor sends a request to any destination while a deadline is bound, it SHALL stop the call if the
remaining time runs out before the response headers arrive, so the effective timeout is the smaller of the client's configured timeout and the
remaining time. It SHALL stop the call by interrupting the calling thread. IF the remaining time runs out before the response headers arrive,
THEN the interceptor SHALL throw `DeadlineExceededException` (stage `TIMEOUT`), with the exception the client threw when it was interrupted as
its cause (DL-12). A response that arrives as the remaining time runs out SHALL be returned. No interrupt the interceptor causes SHALL remain set
on the thread, or arrive, after the interceptor returns or throws. A timeout from the client's own, shorter configuration SHALL propagate
unchanged, and so SHALL a timeout while reading the response body, which happens after the interceptor has returned (DESIGN D57). Stated
limitations: an application interrupt that arrives at the moment the timer fires is cleared too; an interrupt already set before the call is
never cleared, so the JDK client fails at once and the interceptor rethrows its exception unchanged.

v0.1 SHALL support the JDK `HttpClient`-based request factory. The interceptor SHALL arm its timer for every client; the call is cut off where
the request factory honours interrupts: the JDK one; Jetty and Reactor Netty (Jetty only gives up the wait); and the Simple and Apache
HttpComponents factories on virtual threads. The Simple and Apache HttpComponents factories on platform threads are not capped. WHERE the request
factory Spring Boot auto-configures for `RestClient.Builder` and `RestTemplateBuilder` is not the JDK `HttpClient` one, the Spring module SHALL log
one WARN entry at startup stating that timeout capping is not guaranteed, and SHALL still apply DL-05 and DL-06. The WARN reads Boot's
auto-configured `ClientHttpRequestFactoryBuilder`, so it can be wrong when a customizer wraps that builder or the application defines its own
builder for the JDK factory, and a client given its own request factory in code is not checked. The Javadoc of `BoundrailClients` SHALL state
that timeout capping is guaranteed only with the JDK `HttpClient` request factory, which other factories it works with, and these limits of the
WARN (DESIGN D51). Verified by IT-3.2 and unit tests.

**OUT-05** `[M4]` The Javadoc of `BoundrailClients` SHALL state that a 3xx redirect the HTTP client follows is not classified again, so the
redirected request may carry the context headers written for the original destination, and that redirect following should be turned off on
clients declared internal and on clients that reach hosts matched by an internal host pattern (DESIGN D52). Verified by the Javadoc of
`BoundrailClients`.

### Responses and metrics

**DL-08** `[M4]` WHERE `boundrail.context.deadline.map-exception=true` (default), a `DeadlineExceededException` escaping a request handler SHALL
produce the `expired-status` response, through a `HandlerExceptionResolver` of lowest precedence that calls `sendError(expired-status)`, as Spring
does for `ResponseStatusException`. It SHALL run after the application's `@ExceptionHandler` methods, so an application handler for the exception
wins. Only the exception itself SHALL be mapped, not one found as the cause of another exception, and nothing SHALL be mapped once the response
is committed (DESIGN D53). `map-exception` SHALL be bound as a boolean with Spring Boot's binding, which accepts `true`, `on`, `yes` and `1` and
their opposites in any case; IF it is a value Boot cannot convert to a boolean, THEN application startup SHALL fail, even when
`spring.main.lazy-initialization` is `true`. Verified by IT-3.2, IT-3.3 and unit tests.

**DL-09** `[M4]` WHERE the application context has a unique or primary `MeterRegistry` bean, the Spring module SHALL count deadline expiries in the
counter `boundrail.context.deadline.exceeded` in that registry, tagged with `stage` in lowercase, and never in Micrometer's global registry, as
DIAG-03 does for violations (RT-09). Each expiry SHALL be counted once, where the Spring module detects it: `inbound` (DL-04), `before_send`
(DL-06), `timeout` (DL-07) and `executor` (EXE-08, for the `Callable` path the Spring module manages). DL-08 SHALL count only the exceptions with
stage `APPLICATION` it maps; the others were counted when detected. Expiries detected by `BoundExecutors` in `boundrail-context-core` are not
counted, because core has no registry (DESIGN D54). Verified by unit tests.

### Executors

**EXE-08** `[M4]` IF a task submitted to an executor returned by `BoundExecutors.attached` starts after the deadline in its captured snapshot
(EXE-02) has expired, THEN the library SHALL NOT run the task. For a task submitted with `submit`, `invokeAll` or `invokeAny`, it SHALL complete
the task's future exceptionally with `DeadlineExceededException` (stage `EXECUTOR`), so `get` throws `ExecutionException` with it as the cause.
For a task submitted with `execute`, it SHALL pass the exception to the current thread's `UncaughtExceptionHandler`
(`Thread.getUncaughtExceptionHandler()`) and return normally. WHERE Spring MVC is present, IF a handler's `Callable` covered by EXE-09 starts
after its captured deadline has expired, THEN the library SHALL NOT call it, and the request SHALL complete with that exception as the
`Callable`'s result, so DL-08 maps it. That check SHALL be made by a `CallableProcessingInterceptor` whose `preProcess` only checks the
deadline and binds or restores no context (DESIGN D39), registered through a `WebMvcConfigurer`; a `RequestMappingHandlerAdapter` not built by
`WebMvcConfigurationSupport` does not get it, and the Javadoc of `BoundExecutors` SHALL say that the check needs an adapter built by
`WebMvcConfigurationSupport`. The library's interceptor SHALL be registered after the application's `CallableProcessingInterceptor`s, so they
still run (DESIGN D56). Executor beans the Spring module
decorates (EXE-03) in `ATTACHED` mode carry the deadline but SHALL run the
task whether or not it has expired, because their decorator cannot fail the future Spring created; the Javadoc of `BoundExecutors` SHALL say
so. A `DETACHED` executor's snapshot holds no deadline (POL-04), so this never applies to it (DESIGN D55). Verified by IT-3.7 and unit tests.

### Configuration

Properties use the prefix `boundrail.context.`.

Every startup failure caused by invalid configuration also occurs when `spring.main.lazy-initialization` is `true`: a `deadline.default-inbound`
or `deadline.max-inbound` that is zero, negative or not a duration (DL-03), a `deadline.expired-status` outside 400 – 599 or not an integer
(DL-04), and a `deadline.map-exception` that is not a boolean (DL-08).

| Property | Default | Meaning |
|---|---|---|
| `deadline.default-inbound` | unset | Budget applied when no trusted deadline is present (DL-03); a `Duration` with Spring Boot's binding, greater than zero; blank counts as not set; may exceed `max-inbound`; any other value fails startup, even under lazy initialization |
| `deadline.max-inbound` | `30s` | Cap on trusted inbound deadlines (DL-03); a `Duration` with Spring Boot's binding, greater than zero; blank means `30s`; any other value fails startup, even under lazy initialization |
| `deadline.expired-status` | `504` | Status for expired requests (DL-04, DL-08), from 400 to 599; any other value fails startup, even under lazy initialization |
| `deadline.map-exception` | `true` | Map `DeadlineExceededException` to the status (DL-08); a boolean with Spring Boot's binding, and a value Boot cannot convert fails startup, even under lazy initialization |

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
- Timeout capping is guaranteed only with the JDK `HttpClient` request factory in v0.1 (DESIGN D51, was Q1). Spring Framework 7.0.9's
  `JdkClientHttpRequestFactory` applies one `readTimeout` per factory, covering the whole exchange including the body, and offers no
  per-request timeout, so DL-07 interrupts the calling thread instead (spike T001).
- Redirects stay a documented limitation in v0.1 (OUT-05; DESIGN D52, was Q7).
- Deadline behaviour for Kafka messages belongs to [007-kafka](../007-kafka/spec.md) (IT-3.8).
