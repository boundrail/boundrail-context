# boundrail-context — Design

> **Status:** agreed design for v0.1 · **Last updated:** 28 September 2026
> **Companion documents:** [roadmap.md](roadmap.md) (milestones) · [specs/](../specs/) (normative requirements and acceptance
> tests)

This document explains what `boundrail-context` is, why it exists, and every design decision taken so far. It is explanatory. Where it and the specs
(`specs/`) disagree, the specs win and this document gets fixed.

---

## 1. Summary

`boundrail-context` is the context module of **Boundrail**, the parent framework for declared service contracts in Java. It defines what request
context means in a distributed system, where each piece of it may travel, and how deadlines behave as work crosses boundaries.

Micrometer and OpenTelemetry can move context between threads and services. `boundrail-context` adds a contract on top of that movement. It provides
typed context keys and a propagation policy for every key at every kind of boundary. It sets explicit trust rules for which context is accepted and
which may leave. Deadline budgets shrink as a request travels. Diagnostics turn lost context into a test failure instead of a production mystery.

README headline:

> **`boundrail-context` defines what context means, where it may travel, and how deadlines behave across service boundaries.**
> Micrometer can move context. `boundrail-context` defines its contract.

---

## 2. The problem

In a typical Spring microservice, request context has to survive a surprising number of boundaries. That context includes tenant, user, locale,
correlation identifiers and time budget. It crosses the servlet thread, `@Async` methods and thread pools, scheduled jobs, outgoing HTTP calls, Kafka
messages, and eventually other services. Every team writes its own MDC filters, TaskDecorators and interceptors, and the results fail in predictable
ways.

- **Context is silently lost** at an executor nobody decorated. The bug surfaces weeks later as a record saved without a tenant.
- **Context leaks** where it should not go, for example an internal tenant or user header forwarded to a third-party API.
- **Context is forged** because a service trusts an `X-Tenant-Id` header that a public client can set.
- **Deadlines do not travel**, so downstream services keep working long after the original caller gave up.

The mechanical part is largely solved. Micrometer's context-propagation library captures and restores thread-local state across executors and Reactor,
and Spring uses it. OpenTelemetry carries trace context and arbitrary key/value baggage between services. Its own documentation warns that baggage can
reach unintended destinations and has no built-in integrity checks.

What is missing is an application-level answer to four questions:

1. What context exists?
2. Where did it come from?
3. Where is it allowed to go?
4. What happens if it disappears?

That answer is the product.

---

## 3. Positioning

**Boundrail** is an umbrella for *declared service contracts* in Java: declare the rules that must remain true as work crosses service boundaries. Its
tagline is *"every call stays on the bound path."* The original Boundrail concept declares failure behaviour: deadlines, dependencies and failure
policies.

**`boundrail-context`** is its first shipped module and the propagation layer the rest depends on. Failure contracts cannot enforce deadlines across
services without it.

**Naming rule.** The module never presents itself as the whole of Boundrail. Documentation, the README and artifacts say `boundrail-context`;
"Boundrail" refers only to the parent framework and organization. The positioning line is:

> Boundrail provides declared service contracts for Java applications. `boundrail-context` defines what application context means, where it may
> travel, and how deadlines behave across service boundaries.

```
                 BOUNDRAIL — declared service contracts
                                  │
            ┌─────────────────────┼─────────────────────┐
     Context contract      Deadline contract      Failure contracts
     (this project)        (this project)         (Boundrail core, later)
            └──────────┬──────────┘                     │
               Propagation runtime  ◄───────────────────┘ depends on
                       │
          ┌────────────┼────────────┐
         HTTP        Async        Kafka
```

Where `boundrail-context` sits in the ecosystem:

| Concern | Owned by |
|---|---|
| Local runtime | `ScopedValue` (Java 25+), `ThreadLocal` (Java 21–24) |
| Reactive runtime | Reactor Context (future backend) |
| Mechanical interop | Micrometer context-propagation (future bridge) |
| Telemetry | OpenTelemetry |
| Transport | HTTP, Kafka |
| Typed context, policy, trust, deadlines, diagnostics | **`boundrail-context`** |

`boundrail-context` does not replace any of these. It integrates with them.

---

## 4. Design principles (frozen invariants)

These invariants were agreed during design. Changing one requires a new entry in the decision log (§16).

**4.1 Context is immutable and scope-only.** Context cannot be mutated. It can only be rebound for a lexical scope, or captured and explicitly
re-entered. There is deliberately no `set()`. This single rule keeps the same semantics possible across ScopedValue, ThreadLocal and a future Reactor
backend. It also stops the library from becoming another hidden mutable-state mechanism.

**4.2 Key, policy and adapter are separate.** A *key* is a typed semantic value. A *policy* decides where a key may travel. An *adapter* is the
mechanism that carries it across a specific boundary. Keys never accumulate header names, trust rules or executor semantics.

**4.3 Propagation is decided per key and per boundary.** There is no "propagate the context." For each key, the policy states whether it crosses each
boundary type. In-process propagation is the default. Remote propagation is denied unless declared.

**4.4 Destinations are classified, and unknown means external.** Every outbound call is classified as internal, external or unknown before any context
is written. Unknown is treated as external.

**4.5 Inbound trust is explicit.** Context headers from public or unverified callers are untrusted: they are ignored and reported, never bound. At the
edge, identity-derived context comes only from verified authentication.

**4.6 Authority and initiator are different things.** Authority answers "who is currently authorised?" and is sensitive. Initiator answers "who
originally caused this work?" and is a plain audit identifier. They propagate differently.

**4.7 Attached versus detached is declared, never guessed.** Work the caller awaits is *attached*. Fire-and-forget work is *detached*. The library
cannot infer which is which, because a returned future may or may not be awaited. So executors declare it, and the default is detached.

**4.8 Deadlines have semantics.** A deadline is not just another value. It shrinks as time passes and travels as remaining time. It stops at detached
boundaries. It is still *applied* locally, as a timeout cap, even where it is not *propagated*. A child operation can shorten a deadline but never
extend it.

**4.9 Diagnostics report evidence, not guesses.** Violation reports contain only facts the library observed. Diagnostics have an explicit cost model:
cheap by default, detailed in development and tests.

**4.10 Interoperate, don't replace.** Micrometer, OpenTelemetry and Spring stay in charge of what they already do well.

---

## 5. Core concepts

### 5.1 Context keys

A `ContextKey<T>` has a name, a type, and optionally a default value. Values are never null. Applications declare keys as constants:

```java
public final class AppKeys {
    public static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);
    public static final ContextKey<Locale> LOCALE = ContextKey.of("locale", Locale.class, Locale.UK);
}
```

Business code reads them anywhere in the call stack without passing parameters. `AppKeys.TENANT.get()` returns the value or throws
`MissingContextException`, and `find()` returns an `Optional`.

The library ships three built-in keys with special semantics: `boundrail.deadline`, `boundrail.authority` and `boundrail.initiator` (sections 6 and
7).

### 5.2 Snapshots and scopes

The current context is one immutable `ContextSnapshot`. Code changes context only by opening a scope:

```java
// Rebind for a lexical scope
Context.with(AppKeys.TENANT, "acme")
       .run(() -> reindexService.reindex());

// Capture and explicitly re-enter elsewhere
ContextSnapshot snapshot = Context.capture();
executor.execute(() -> snapshot.run(service::process));
```

Nested scopes may rebind a key; the deadline is the one exception (§7). Leaving a scope always restores the previous snapshot, including when an
exception is thrown. Capturing is O(1), because it hands out the current immutable snapshot.

### 5.3 Runtime backends

The runtime keeps the current snapshot in one slot per thread of execution. Two backends provide that slot with identical semantics:

- **ThreadLocal backend (Java 21–24).** The slot is set and restored in `try/finally` and removed when the outermost scope exits, so pooled threads
  carry no residue.
- **ScopedValue backend (Java 25+).** `ScopedValue` is final in Java 25. Its bounded lifetime and cheap inheritance fit the scope-only model exactly.

A multi-release JAR selects the backend automatically. ScopedValue is an implementation detail, not the product's identity, and a future Reactor
backend must fit the same scope-only API.

Scoped values are inherited automatically by subtasks forked with `StructuredTaskScope`, but that API is still preview in Java 25, so v0.1 does not
rely on it. Thread pools, `CompletableFuture` and `@Async` never inherit context on their own. Bridging them is the library's job.

### 5.4 Boundaries

| Boundary | Meaning | Example |
|---|---|---|
| `ATTACHED` | In-process work the caller awaits | a task on an executor declared attached |
| `DETACHED` | In-process fire-and-forget work | `@Async void`; the default for executors |
| `INTERNAL_CALL` | Synchronous call to a service inside the trust domain | RestClient call to the orders service |
| `EXTERNAL_CALL` | Synchronous call outside it, or to an unknown destination | a payment provider's API |
| `MESSAGE` | Asynchronous message on an internal broker | a Kafka record |

`MESSAGE` is detached by nature. The consumer processes the record on its own schedule, possibly minutes later.

### 5.5 Policy

For every key, the policy states which boundaries the key crosses, whether it is *required*, and whether it may be *logged*. For application keys,
remote boundaries also need a *codec* and a *wire name*; the built-in keys have fixed wire formats instead. Keys not registered in the policy stay
in-process, crossing `ATTACHED` and `DETACHED` only.

```java
@Bean
ContextPolicy contextPolicy() {
    return ContextPolicy.builder()
        .key(AppKeys.TENANT, k -> k
            .codec(Codecs.string())
            .wireName("Tenant-Id")
            .required()
            .loggable()
            .propagateTo(ATTACHED, DETACHED, INTERNAL_CALL, MESSAGE))
        .build();
}
```

Default policies:

| Key | ATTACHED | DETACHED | INTERNAL_CALL | EXTERNAL_CALL | MESSAGE | Loggable |
|---|---|---|---|---|---|---|
| `boundrail.deadline` | yes | no | yes, as remaining time | not sent, but still caps the timeout | no | no |
| `boundrail.authority` | yes | no | yes, forwarded or exchanged | never | never | never |
| `boundrail.initiator` | yes | yes | yes | no | yes | yes |
| `tenant` (example app key) | yes | yes | yes | no | yes | yes |
| any unregistered key | yes | yes | no | no | no | no |

The built-in keys' rules are fixed in v0.1. An application policy cannot re-register them, so authority can never be made loggable or sent further.

A policy cell can have two effects: *propagate* (send or carry the value) and *apply* (enforce it locally). Only the deadline has an apply effect: it
caps outbound timeouts on every call, including external calls where it is not sent.

A required key that is missing when an adapter is about to make a remote crossing the key propagates to is a *violation* (§10).

### 5.6 Adapters

Adapters carry context across one boundary type:

- servlet filters for inbound HTTP;
- a client interceptor for `RestClient` and `RestTemplate`;
- task decorators and executor wrappers for in-process work;
- Kafka producer and consumer adapters for messages.

Every adapter is *around-style*: it opens a scope for exactly the duration of the work. Set/restore hooks alone are not enough, because they cannot
drive the ScopedValue backend.

---

## 6. Identity: authority and initiator

**Authority** (`boundrail.authority`) is who is currently authorised: a subject plus an opaque credential (a bearer token).

- It is only ever established from a verified Spring Security `Authentication`, never decoded from a context header.
- It propagates to internal calls only. It never reaches detached work, external calls, messages, logs or diagnostic output.
- Its `toString()` never reveals the credential.

Propagation to internal calls goes through an `AuthorityPropagator`. Forwarding the original token is the simplest mode. However, audience-scoped
tokens (issued per target service) cannot be forwarded, so **token exchange (RFC 8693) is a first-class mode** alongside forwarding. If application
code has already set an `Authorization` header on a request, the module respects it.

**Initiator** (`boundrail.initiator`) is who originally caused the work: a plain identifier such as `user:123`, `service:billing` or
`system:scheduled:cleanup`. It is an audit fact, not a permission, so it flows everywhere internal, including detached work and Kafka. At the edge, it
is derived from the authenticated principal. From a trusted internal caller, the incoming initiator header takes precedence, because after token
exchange the token's subject may be a service rather than the original user.

The result is a clean messaging story:

```
HTTP request             authority = JWT identity      initiator = user:123
   └─► Kafka record ─► consumer:   authority = absent    initiator = user:123
```

The consumer knows who caused the event without pretending the original user's authority is still active.

---

## 7. Deadlines

A deadline is an instant on a monotonic clock with a small, semantic API: `remaining()`, `isExpired()`, `check()` and `within(Duration)`. Across the
wire it travels as **remaining milliseconds** in the `Boundrail-Deadline-Ms` header, never as an absolute timestamp, so clock skew between machines
does not matter. The receiver computes its own deadline from the time of arrival. Network transit time is not subtracted in v0.1, which is a known
limitation.

Behaviour at each point:

- **Inbound.** A trusted caller's deadline is accepted, capped by a configured maximum. Untrusted callers cannot set deadlines, but the edge can apply
  a configured default. A request that arrives already expired is rejected (default 504) without running the handler.
- **Outbound.** Internal calls send the remaining time. Every outbound call, internal or external, uses the smaller of its configured timeout and the
  remaining budget. A call attempted after expiry fails fast with `DeadlineExceededException` and is never sent.
- **Nested scopes** can shorten the deadline but never extend it. Binding a later deadline keeps the current one, and removing the deadline in a
  nested scope is rejected. Work that must outlive the request goes through a detached executor or an explicit snapshot re-entry, both of which
  replace the context rather than extend it.
- **Attached executors** do not start a task whose deadline has already passed.
- **Detached executors and Kafka messages** do not receive the deadline at all. Events and fire-and-forget work outlive the request. If they inherited
  its budget, consumers would drop messages whenever there was lag.

```
Client 500ms ─► A (421ms left) ─► B (267ms left) ─► C (91ms left) ─► slow call ─► FAIL FAST
A ─► Kafka event (no deadline) ─► consumer, 4s later ─► still processed
```

Deadlines are what connect this project to Boundrail's failure contracts. A declared timeout means nothing if the caller's budget is already gone.

---

## 8. Trust boundaries

**Outbound.** A `DestinationClassifier` classifies each outbound call as `INTERNAL`, `EXTERNAL` or `UNKNOWN`. The classifier lives in the core module
and works on a transport-neutral `OutboundRequest` (the target URI plus any per-client declaration). Every adapter, today's HTTP clients and later
WebClient, Feign or gRPC, therefore classifies through the same code instead of reimplementing trust decisions. The default classifier checks, in
order:

1. a per-client declaration, since hostnames alone are not always a trustworthy architectural boundary;
2. configured internal host patterns, such as `*.svc.cluster.local`;
3. configured external host patterns.

Anything else is `UNKNOWN` and handled as `EXTERNAL`. For external destinations, the adapter also strips any policy-governed header that application
code set by hand, so a key declared internal-only cannot leave the network by accident. The `Authorization` header is exempt, because on external
calls it belongs to the application.

**Inbound.** An `InboundTrustVerifier` decides whether a caller is a trusted internal service. The default trusts nobody, so a fresh install binds no
context from headers. Built-in support covers trusted-network CIDRs, and custom verifiers can check service-mesh identity or a gateway that strips
context headers from outside traffic. Untrusted context headers are ignored and reported, never fatal. Signed context envelopes are future work.

**At the edge**, context comes from verified authentication through `ContextContributor`s, for example one that maps a JWT claim to the tenant key.
Contributors run after Spring Security, in a nested scope. That also avoids the classic bug of capturing context before authentication has finished.

---

## 9. Executors and async work

Every managed executor has a mode, `ATTACHED` or `DETACHED`, defaulting to `DETACHED`. The mode is declared explicitly because the library cannot know
whether a caller awaits the result. Detached is the conservative default: wrongly dropping a deadline only wastes some work, while wrongly propagating
one silently drops legitimate work.

- The Spring module automatically adds a context task decorator to Spring's task executors and schedulers, composing with any decorator already
  present.
- Plain `Executor` and `ExecutorService` beans are not wrapped automatically, because wrapping them would break injection by concrete type. The
  startup audit flags them, and `BoundExecutors.attached(...)` or `BoundExecutors.detached(...)` wraps them explicitly.
- `@Scheduled` jobs start in a fresh system context whose initiator names the job, not an empty or accidental one.
- `CompletableFuture.supplyAsync` without an explicit executor runs on the common pool and is not covered. This is documented.

A consequence of the detached default: authority never reaches detached work, so an `@Async` method that calls an internal service sends no
`Authorization` header and may be rejected downstream. This is intended, because fire-and-forget work should not borrow a user's authority after the
request is gone. The documentation recommends an attached executor when the caller awaits the result, and a service credential obtained by the task
otherwise.

---

## 10. Violations and diagnostics

A *violation* is a context rule broken at runtime. The kinds are:

- missing required key;
- untrusted header;
- invalid value;
- oversized value;
- forbidden header on an external call;
- missing audience for token exchange.

Only a missing required key can fail the operation, and only in `fail` mode, where the adapter throws `ContextViolationException` before crossing the
boundary. In `report` mode it logs, counts and continues without the key. All other kinds are always report-only, because external clients may send
anything. The one exception lies outside the violation model: when an application codec's `decode` throws something other than
`IllegalArgumentException`, or its `encode` throws anything, that is an application bug, and the exception propagates and fails the operation
with no violation reported (WIRE-05). Reports are rate-limited log entries, plus a Micrometer counter when Micrometer is present.

Reports state evidence only:

- the key, the boundary and the destination;
- the current thread;
- the capture site of the current snapshot, when it was recorded;
- whether the thread name matches an executor the audit flagged.

The library never claims "context was lost at `@Async` → Executor" unless it observed that transition.

Recording capture sites requires stack walking, so the cost model is explicit. It is off in production by default, on in development and tests, and
optionally sampled in between.

At startup, an **audit** lists every executor bean and whether it is covered, optionally failing startup when there are gaps. The audit is best
effort: it only sees Spring beans. Executors created inside code are invisible to it, which is why runtime validation remains the safety net.

The `boundrail-context-test` module turns all of this on for tests with one annotation. **Context loss becomes a test failure instead of a production
mystery.**

---

## 11. Logging

Keys marked loggable are mirrored into SLF4J's MDC for the duration of a scope and restored on exit, so `%X{tenant}` works in log patterns without
extra code. Authority and deadline are never logged.

Core has no SLF4J, so it exposes a small `ScopeListener` hook that is told when any scope is entered and exited, and the Spring module's MDC bridge is
one listener. Listeners and the capture-site mode are the only library state that applies to the whole JVM, apart from the test module's
violation recorder (D40). Everything else, such as the policy and violation reporting, belongs to one Spring application context.

---

## 12. Architecture and modules

```
ContextKey       name + type + default                        (semantic value)
ContextPolicy    boundaries, required, loggable, codec, wire  (decision)
ContextSnapshot  immutable, transport-neutral                 (state)
Destinations     INTERNAL / EXTERNAL / UNKNOWN, transport-neutral  (classification)
Runtime          ThreadLocal | ScopedValue | (Reactor later)  (mechanism, local)
Adapters         servlet · RestClient/RestTemplate · executors · Kafka  (mechanism, boundaries)
```

The repository is `boundrail/boundrail-context`, separate from Boundrail core. Boundrail core depends on it, never the reverse.

| Project | Contents | Published |
|---|---|---|
| `boundrail-context-core` | keys, snapshots, scopes, policy, codecs, destination classification, deadline and identity types, both backends; zero runtime dependencies | yes |
| `boundrail-context-spring` | Spring Boot 4 auto-configuration: servlet filters, trust, RestClient/RestTemplate, executors, `@Scheduled`, MDC, audit, security contributors | yes |
| `boundrail-context-kafka` | Spring Kafka producer and consumer adapters | yes |
| `boundrail-context-test` | JUnit Jupiter extension, assertions, test trust verifier | yes |
| `integration-tests` | runtime conformance suite and the four acceptance suites, run against packaged JARs on JDK 21 and 25 | no |
| `examples/propagation-demo` | runnable demo used by the README | no |

---

## 13. Technology and build

**Java.** The minimum runtime is Java 21. This is the project's choice, not Spring's: Spring Boot 4 still supports Java 17. Java 25+ gets the
ScopedValue backend. Supporting 17 would add little code for the ThreadLocal backend, but a Java 21 floor lets the codebase use modern language
features. Revisit only if users ask.

**Build.** The build uses Gradle with the Kotlin DSL, a version catalog, and convention plugins in `build-logic`. The core module compiles its main
code for release 21 and a `java25` source set for release 25. The `java25` classes are packaged under `META-INF/versions/25` of a multi-release JAR,
so building requires a JDK 25 toolchain.

**Testing.** Multi-release JARs only take effect when classes load from a JAR. Unit tests that run against compiled class directories therefore use
the ThreadLocal backend even on JDK 25. To handle this:

- backend-sensitive tests and acceptance tests live in `integration-tests`, which consumes the packaged JARs;
- that project runs through Gradle toolchain launchers on JDK 21 and on JDK 25;
- a test asserts which backend is active in each run;
- Kafka tests use Testcontainers and exercise the real serialisation and interceptor path, never mocks.

**Stack.** Spring Boot 4.x (Spring Framework 7), Spring Kafka, Spring Security (optional at runtime), Micrometer (optional), SLF4J (Spring module
only), JUnit 6 (managed by Spring Boot), AssertJ and Testcontainers. CI runs on GitHub Actions with both JDKs installed.

**Licence.** Apache 2.0 for this project, even if Boundrail core later chooses something more protective. Libraries that everyone embeds only spread
when legal teams don't have to think about them.

**Process.** Development is spec-driven, in the GitHub Spec Kit layout. Each milestone is a feature folder under `specs/` holding its numbered
requirements, acceptance scenarios, configuration, plan and tasks. The principles and spec conventions live in `.specify/memory/constitution.md`. The
acceptance suites are written first and are the executable specification. Any change in behaviour starts as a spec change.

---

## 14. Non-goals for v0.1

The following are out of scope for v0.1. Most are candidates in roadmap.md.

- WebFlux, WebClient and a Reactor backend
- Feign, gRPC, RabbitMQ and JMS
- Batch Kafka listeners (a manual helper is provided)
- Signed context envelopes and built-in service-mesh identity verification
- Bridges to Micrometer context-propagation and OpenTelemetry
- Database query timeouts derived from deadlines
- Java 17 support

`boundrail-context` is also not a tracing system, not a service mesh, and not a replacement for Spring Security.

---

## 15. Validation plan

Before investing beyond v0.1, test the idea with experienced Spring developers using three demos:

1. context loss caught by a test;
2. a tenant stripped from an external call;
3. a deadline shrinking across hops while a Kafka event is unaffected.

Reactions like these identify the real product:

- *"Can it tell me when one of my executors isn't propagating context?"*
- *"Can I declare that this header must never leave my network?"*
- *"Can the deadline automatically become my HTTP client timeout?"*

"Would you use it?" is not a useful question.

---

## 16. Decision log

### Agreed

| # | Decision | Rationale |
|---|---|---|
| D1 | Build context propagation as the first Boundrail module, published as `boundrail-context`. | It is the easiest piece to adopt, it funnels users into Boundrail, and Boundrail's deadlines need it. |
| D2 | Separate repository under the `boundrail` GitHub org. Boundrail core depends on it, never the reverse. | Clean licensing, independent releases, standalone identity, enforced dependency direction. |
| D3 | Apache 2.0 licence. | Adoption of an embedded library. |
| D4 | Position as policy, typed context, trust and deadlines on top of Micrometer/OTel, not a replacement. | "Micrometer already does that" is the main competitive objection. |
| D5 | Context is immutable and scope-only, with no setters. | Runtime-neutral semantics and no hidden mutable state. |
| D6 | Key, policy and adapter are separate. | Keys stay semantic instead of becoming configuration objects. |
| D7 | Propagation is per key and per boundary; remote propagation is denied unless declared. | Expressive, and safe by default. |
| D8 | Destinations are classified, with `UNKNOWN` treated as `EXTERNAL`; per-client declarations override host patterns. | Secure default; hostnames are not always a trustworthy boundary. |
| D9 | Inbound trust is explicit; untrusted context headers are ignored and reported. Signed envelopes come later. | Prevents forged context. |
| D10 | Authority and initiator are split; authority comes only from verified authentication; token exchange is first-class. | Prevents impersonation, supports audience-scoped tokens, and gives a clean audit story for Kafka. |
| D11 | Attached/detached is declared per executor, defaulting to detached. | It cannot be inferred, and detached is the safer failure. |
| D12 | The deadline is a semantic type; its wire format is remaining ms; it is dropped at detached boundaries; propagate and apply are separate effects. | Avoids clock skew and dropped messages, and keeps external calls protected. |
| D13 | Diagnostics report evidence only; capture sites are cost-controlled; the audit is best effort. | Honest, and cheap in production. |
| D14 | Java 21 floor (the project's choice); ScopedValue backend on 25+ through a multi-release JAR. | Modern baseline, with the best backend where available. |
| D15 | Gradle with the Kotlin DSL. | The maintainer's choice. |
| D16 | Spec-driven development: SPEC.md plus acceptance suites written first, run against packaged JARs on JDK 21 and 25. | Tests are the specification, and multi-release JARs only take effect from JARs. |
| D17 | Validate with developers after v0.1 before expanding scope. | Proves demand for the policy layer before building a platform. |
| D18 | Boundrail is the parent framework and organization; this module is always called `boundrail-context` and never presents itself as the whole framework. | Leaves room for other Boundrail modules and keeps the positioning honest. |
| D19 | A child operation can shorten a deadline but never extend it: rebinding keeps the earlier deadline, and removing it in a nested scope is rejected. | A deadline that inner code can extend protects nothing. |
| D20 | Destination classification lives in core, on a transport-neutral `OutboundRequest`. | Future adapters reuse one classifier instead of each reimplementing trust decisions. |
| D21 | Context values are non-null; absence means "not bound". (was P1) | Removes the missing/null/present ambiguity. |
| D22 | Wire names are `Boundrail-Deadline-Ms` and `Boundrail-Initiator`; authority uses the standard `Authorization` header; application keys declare their wire names explicitly, and may not use `Authorization` or the `Boundrail-` prefix. (was P2) | No `X-` prefix (RFC 6648), explicit names are easy to review, and reserved names cannot bypass the external-call rules. |
| D23 | An expired inbound deadline, or an unhandled `DeadlineExceededException`, returns 504 (configurable). (was P3) | Matches how gRPC gateways map `DEADLINE_EXCEEDED` to HTTP. |
| D24 | Violation mode defaults to `fail`; the docs recommend `report` for a first production rollout. (was P4) | Loud by default, with a safe migration path. |
| D25 | Kafka message headers are trusted by default for keys whose policy includes `MESSAGE`; authority and deadline are never read from messages. (was P5) | The broker is internal, and the dangerous keys are excluded regardless. |
| D26 | Key policies are declared in code; environment-specific settings (hosts, trust, modes) live in configuration properties. (was P6) | Type safety for contracts, flexibility for environments. |
| D27 | Values from trusted headers take precedence over contributors, except authority, which always comes from authentication. (was P8) | Preserves the original initiator across token exchange. |
| D28 | Only Spring's task executor and scheduler types are auto-decorated; plain `ExecutorService` beans must be wrapped explicitly. (was P9) | Automatic wrapping would break injection by concrete type. |
| D29 | Core logs through `System.Logger` and has zero runtime dependencies. (was P10) | Embeddable anywhere. |
| D30 | Built-in key rules are fixed in v0.1; applications cannot re-register built-in keys. | One simple rule instead of narrowing semantics; can be loosened later without breaking anyone. |
| D31 | Core exposes a `ScopeListener` hook; the only JVM-wide state is the current-snapshot slot, the listeners and the capture-site mode. | Lets the Spring module mirror every scope into the MDC, and keeps several application contexts in one JVM independent. |
| D32 | Specs follow the GitHub Spec Kit layout: one feature folder per milestone under `specs/` (`###-feature-name`, numbered in development order) with `spec.md`, `plan.md` and `tasks.md`; SPEC.md keeps the shared parts; requirement IDs are unchanged. | Smaller files per feature, Spec Kit tooling, and stable IDs for tests and traceability. |
| D33 | `docs/SPEC.md` is removed. Its conventions move to the constitution, the glossary to design.md Appendix A, the test topology to `specs/001-foundation`, configuration properties to each feature's spec, and traceability to roadmap.md. | One home for each piece of content, with no overview file to keep in sync. |
| D34 | Doc file names under `docs/` are lowercase: `design.md` and `roadmap.md`. | Consistent, conventional file names. |
| D35 | No violation report of any kind, nor its log entry, contains the value it concerns or the message of a codec exception; adapters identify the value by key name, wire name and boundary. Adapters enforce one maximum value length (`wire.max-value-length`, in UTF-16 code units as `String.length()` counts them) on wire values for all codecs, checked before `decode`; codecs stay limit-free. Only an `IllegalArgumentException` from `decode` is a decode failure; any other exception from a codec's `decode`, and any exception from its `encode`, including an `IllegalArgumentException`, propagates unchanged. Values from verified authentication, such as JWT claims, are not wire values and are not length-checked; WIRE-04 applies if such a value is later sent on the wire, except the `Authorization` credential (amended by D60). (was Q6) | Codec messages echo the raw input, which is untrusted, unbounded, may contain CR or LF, and may belong to a key that is not loggable. One check in the adapter protects every codec, including application codecs, without wrapping the JDK's exceptions. `IllegalArgumentException` is the rejection the codec contract defines (WIRE-03); anything else is a bug in the codec, which reporting it as an invalid value would hide. `String.length()` is what Java code measures and costs nothing to compute. A claim is bounded by the verified token it came from, not by an untrusted header. |
| D36 | `ContextInboundFilter` is ordered one below `spring.security.filter.order` (-101 with Spring Boot's default of -100), so it runs before Spring Security's filter chain wherever that chain is configured. When it binds no keys, the rest of the chain runs in the caller's existing scope, with no empty scope entered. ([003-http-trust](../specs/003-http-trust/spec.md) IN-01) | A fixed order would fall behind the security chain when an application moves it. Entering an empty scope would add nothing a handler can observe. |
| D37 | `boundrail-context-test` depends on `boundrail-context-spring` as `api`, and on `spring-web` and `jakarta.servlet-api` as `compileOnly`. ([003-http-trust](../specs/003-http-trust/spec.md) TEST-01) | `TestInboundTrustVerifier` implements `InboundTrustVerifier`, so `-spring` is part of `-test`'s public API and consumers need it on their classpath. Only `peer()` uses `spring-web`, and any application that calls it already has it. Only a servlet container calls `verify`, and any servlet application provides the servlet API. |
| D38 | Where Spring Security is on the classpath, `ContextAuthenticationFilter` is ordered one above `spring.security.filter.order` (-99 with Spring Boot's default of -100), so it runs after Spring Security's filter chain wherever that chain is configured; a property of `Integer.MAX_VALUE` fails startup. An exception from the rest of the chain, including an undeclared checked exception, propagates from it unchanged. ([003-http-trust](../specs/003-http-trust/spec.md) IN-04) | The mirror of D36: a fixed order would fall before the security chain, and so before authentication, when an application moves it. No order exists one above `Integer.MAX_VALUE`, and silently clamping would put the filter at the chain's own order. The filter adds context, not error handling. |
| D39 | Spring MVC async processing keeps the request's context: the library stores the request's innermost snapshot as a request attribute on the initial dispatch, and `ContextInboundFilter`, also registered for `ASYNC` dispatches, re-enters it around each async dispatch without verifying trust, decoding headers or reporting again; `ContextAuthenticationFilter` does not run on async dispatches. A handler's `Callable` runs inside the request's snapshot filtered for `ATTACHED`, whatever the mode of MVC's executor. (was Q8; [004-async-diagnostics](../specs/004-async-diagnostics/spec.md) IN-09, EXE-09) | Re-entering a stored snapshot is around-style, so it works on the ScopedValue backend, and it reports each untrusted header and runs each contributor once per request, which re-running the filters would not. The request awaits a `Callable`'s result, so it is attached work. |
| D40 | `@BoundrailTest` turns capture sites on for a test class and restores the previous mode afterwards, and records the violations of every application context in the JVM through a `ViolationListener` the test module auto-configures; it does not change the violation mode. The recorder is the one JVM-wide state allowed outside RT-09's list, because the test module is never on a production classpath. `boundrail-context-test` exposes JUnit Jupiter and AssertJ as `api` dependencies, and its assertions extend `AbstractAssert`. ([004-async-diagnostics](../specs/004-async-diagnostics/spec.md) TEST-02, TEST-04, DIAG-11, RT-09) | The violation mode is a per-context property that a JUnit extension cannot set for contexts started outside it, and `fail` is already the default (D24). Violation reporting belongs to each context, so a listener hook is the only way to see them from a test. Consumers of the assertions need both libraries on their test classpath. |
| D41 | The Spring module applies `diagnostics.capture-sites` and `diagnostics.sample-rate` only when they are set, so an unset property leaves the JVM-wide mode unchanged. A capture site is held by the snapshot (`ContextSnapshot.captureSite()`), in `StackTraceElement.toString()` form, and is the first frame outside `boundrail-context-core`'s packages, so a snapshot an adapter creates records the adapter. Policy filtering and re-entering keep it. ([004-async-diagnostics](../specs/004-async-diagnostics/spec.md) DIAG-05, DIAG-10) | Applying a default of `off` from every starting context would undo a mode set by a system property, by another context or by a test. Skipping only core frames gives the application's frame for application bindings and a meaningful frame, rather than servlet-container code, for adapters. |
| D42 | Executor evidence is `<bean> (<status>)`, such as `rawExecutor (GAP)` or `taskExecutor (COVERED detached)`, taken from a table of executor beans built at startup whether or not the audit is enabled; the longest matching prefix wins. The audit logs one fixed-format entry per executor bean (INFO when covered, WARN for a gap) and a summary; the violation WARN entry includes the capture site and the executor evidence. ([004-async-diagnostics](../specs/004-async-diagnostics/spec.md) DIAG-04, DIAG-06) Between equally long thread-name prefixes the first bean name in ascending order wins, and an empty prefix matches nothing. Executors produced by a `FactoryBean` are not in the table or the audit (DIAG-12), which the `BoundExecutors` Javadoc states. An `executors.modes` key counts as matched when it names any bean definition of an EXE-03 type, lazy and prototype ones included; `audit.enabled` and `audit.fail-on-gap` are bound as Boot booleans (`yes`, `on` and `1` are true) and fail startup when Boot cannot convert them. Lazy `ThreadPoolTaskExecutor` and `ThreadPoolTaskScheduler` beans are `SmartLifecycle` beans that Spring creates at refresh, so the table and audit see them as instantiated. | A fixed string states the observed fact without guessing a cause (principle V). Log entries are what operators see and what the acceptance tests already capture, so no public audit API is needed in v0.1. |
| D43 | `BoundExecutors` has overloads that take a `ContextPolicy`; the overloads without one filter with `BuiltInKeys.defaultPolicy()`, and their Javadoc says that application rules at `ATTACHED` and `DETACHED` are then not applied. Executors the Spring module decorates use the effective policy. ([004-async-diagnostics](../specs/004-async-diagnostics/spec.md) EXE-02, EXE-04) | Core cannot see a Spring application's policy (RT-09), and explicit wrapping should not need Spring. |
| D44 | The MDC bridge mirrors the current snapshot exactly: on scope entry it puts each bound loggable key and removes each unbound one, and restores the previous values on exit. Each application context registers its own listener, with its own policy's loggable keys, and removes it when it closes. ([004-async-diagnostics](../specs/004-async-diagnostics/spec.md) LOG-01) | A nested scope that unbinds a key must not keep logging the outer value. A listener per context keeps each policy in charge of its own keys and leaves nothing behind when a context closes. |
| D45 | The documentation that executor coverage owes in M3 (common-pool `CompletableFuture` methods, audit limits) is the Javadoc of `BoundExecutors`; the README takes it up in M7 (REL-01). ([004-async-diagnostics](../specs/004-async-diagnostics/spec.md) EXE-07, DIAG-07) | It ships and is reviewed with the feature, so the requirement is verifiable when M3 closes. |
| D46 | Settled while planning 004: `executors.modes.<bean-name>` applies only to executor beans the library decorates, matched by bean name ignoring case, with conflicting modes for one name failing startup; `BoundExecutors` take their mode from the method. `@Scheduled` gets a fresh system context only when the scheduler invokes it. The executor table and audit cover only singleton executor beans of the context itself instantiated at startup. `ScopeListener` registration is idempotent, removing an unknown listener is a no-op, a `null` restorer stops the operation, runs the restorers already returned, and throws, and only `RuntimeException`s from listeners and restorers are caught, logged with the listener's name; an `Error` from `onEnter` runs the restorers already returned and then propagates, and an `Error` from a restorer propagates at once. Violation listeners follow the same rule: a `RuntimeException` is logged at WARN and an `Error` propagates (DIAG-11). Outside a `@BoundrailTest` test method the recorder throws `IllegalStateException`, and parallel test methods are unsupported. `boundrail-context-test` ships one auto-configuration, the recorder's `ViolationListener`. ([004-async-diagnostics](../specs/004-async-diagnostics/spec.md) EXE-01, EXE-06, DIAG-11, DIAG-12, RT-07, TEST-02) A violation reaches only the `ViolationListener` beans of the context that reports it, not its ancestors' (DIAG-11; a prototype-scoped listener of an ancestor is not excluded, since that would mean re-implementing Spring's bean ordering), as the executor table and audit cover only the context's own beans (DIAG-12). | Environment variables lowercase bean names. A direct call by application code is not a scheduled run, so it keeps its caller's context. Instantiating lazy or prototype beans, or reading parent contexts, would make the audit change the application it observes. Catching `Error` would hide JVM failures. A JVM-wide recorder cannot tell parallel tests apart. |
| D47 | The Spring module reads, once per bean, the private `taskDecorator` field of `ThreadPoolTaskExecutor`, `SimpleAsyncTaskExecutor` and `ThreadPoolTaskScheduler`, and the private `taskExecutor` field of `RequestMappingHandlerAdapter`, and sets values only through Spring's public setters; if such a field is missing at runtime, startup fails. `@Scheduled` system contexts cover ordinary methods only, not reactive or Kotlin `suspend` ones, and come from replacing only Spring's own `internalScheduledAnnotationProcessor` definition; an application-registered one is left alone with a startup WARN. (spike T001, was gap G1; [004-async-diagnostics](../specs/004-async-diagnostics/spec.md) EXE-03, EXE-06, EXE-07, EXE-09, EXE-10) An executor bean first created after startup fails when it is created. One executor instance decorated under two bean names with different modes fails startup (EXE-01). A lazy bean that completes such a conflict after startup fails when it is created. The startup audit warns about each `executors.modes` key that matches no decorated executor (DIAG-06). The `RequestMappingHandlerAdapter` field is checked when the library's post-processor is created, so startup fails even when the adapter is created lazily at the first request. | The T001 spike found that Spring 7 has no getter for these values, so reflection is the only way to compose with an existing decorator and to cover MVC's async executor as EXE-03 and EXE-09 require. Failing at startup turns a Spring upgrade that renames a field into an immediate, named error instead of silently lost context. Replacing an application's own post-processor would override a deliberate choice. |
| D48 | `DeadlineExceededException` has public constructors `(Stage)` and `(Stage, Throwable cause)`; `null` arguments throw `NullPointerException`. ([005-deadlines](../specs/005-deadlines/spec.md) DL-12, CORE-15) | The Spring module throws it at the `BEFORE_SEND`, `TIMEOUT` and `EXECUTOR` stages, and later adapters (Kafka, Boundrail core) will too; the cause keeps the client's timeout exception. |
| D49 | A trusted `Boundrail-Deadline-Ms` is valid only as ASCII digits; a value too large for a `long` is valid and capped by `max-inbound`. An invalid or repeated header binds nothing, is reported once as `INVALID_VALUE` (or `OVERSIZED_VALUE`), and `default-inbound` then applies as if it were absent. `default-inbound` and `max-inbound` must be greater than zero, a blank `default-inbound` is unset, and `default-inbound` may exceed `max-inbound`, which caps headers only. ([005-deadlines](../specs/005-deadlines/spec.md) DL-03) | Matches how IN-02 and WIRE-05 treat other trusted headers, and a request whose budget header was garbled still gets the service's default budget. Strict digits avoid `Long.parseLong`'s signs and overflow errors. |
| D50 | The outbound header carries the remaining time rounded down to a whole millisecond. When a deadline is bound, a `Boundrail-Deadline-Ms` set by application code on an internal call is replaced by the smaller of its valid value and the remaining time, an exception to OUT-04. The expiry check comes before the required-key check, so an expired call reports no violation. ([005-deadlines](../specs/005-deadlines/spec.md) DL-05, DL-06) | Rounding up, or honouring a larger application value, would let a child extend the budget (§4.8). A call that is never sent needs no violation, and the expiry check is the cheapest. |
| D51 | Timeout capping is guaranteed only with the JDK `HttpClient` request factory. The interceptor arms a timer at the remaining time for every client and, if it fires before the response headers arrive, interrupts the calling thread and throws `DeadlineExceededException` (`TIMEOUT`) with the client's exception as cause; a response arriving as the timer fires is returned, and no interrupt it causes is left set or arrives later. An application interrupt arriving as the timer fires is cleared too, and an interrupt set before the call is never cleared (the JDK client fails at once and its exception is rethrown). Interrupts cut off the JDK, Jetty (which only gives up the wait) and Reactor Netty factories, and the Simple and Apache HttpComponents factories on virtual threads; those two are not capped on platform threads. The Spring module logs one WARN at startup, saying capping is not guaranteed, when the request factory Boot auto-configures for `RestClient.Builder` and `RestTemplateBuilder` is not the JDK one; the WARN can be wrong when a customizer wraps Boot's `ClientHttpRequestFactoryBuilder` or the application defines its own builder for the JDK factory, and clients given their own factory in code are not checked, which the `BoundrailClients` Javadoc states. (was Q1; spike T001, option A; [005-deadlines](../specs/005-deadlines/spec.md) DL-07) A call made while or after the context closes, when the timer's scheduler is shut down, runs without the cap rather than failing, so shutdown never breaks a call that would otherwise work. | The T001 spike found that Spring Framework 7.0.9's `JdkClientHttpRequestFactory` has no per-request timeout: its one `readTimeout` per factory covers the whole exchange, including the body. Option A, interrupting the calling thread, needs no access to the factory and works for every factory that honours interrupts. Option B, a per-request timeout on the JDK factory, was rejected: it needs Spring's private fields, which EXE-10 limits, gives no capping for factories built in code, and would also cap the body read. |
| D52 | Redirects the HTTP client follows stay a documented limitation in v0.1: the `BoundrailClients` Javadoc tells applications to turn off redirect following on internal clients. (was Q7; [005-deadlines](../specs/005-deadlines/spec.md) OUT-05) | The redirect happens below the interceptor, so re-classifying each hop means owning redirect handling, and turning redirects off for every client would change application behaviour. |
| D53 | An expired inbound request gets the configured status with an empty body, set without `sendError`. An escaping `DeadlineExceededException` is mapped by a lowest-precedence `HandlerExceptionResolver` that calls `sendError`, after the application's `@ExceptionHandler` methods; only the exception itself is mapped, not a wrapped cause. `expired-status` must be 400 – 599. ([005-deadlines](../specs/005-deadlines/spec.md) DL-04, DL-08) The DL-08 resolver is appended to Spring MVC's resolver chain through a `WebMvcConfigurer`, not declared as a `HandlerExceptionResolver` bean, because a lone bean of that type would stop `DispatcherServlet` installing its default resolvers where MVC is not configured by `WebMvcConfigurationSupport`. | The inbound check runs before Spring Security, whose error dispatch could replace a 504 at the edge. `sendError` matches Spring's handling of `ResponseStatusException`, and an application that handles the exception itself keeps control. An expiry is an error, so only error statuses make sense. |
| D54 | The counter is `boundrail.context.deadline.exceeded`, tagged `stage` (`inbound`, `before_send`, `timeout`, `executor`, `application`), in the context's unique or primary `MeterRegistry`. Each expiry is counted once where the Spring module detects it; the exception mapping counts only `APPLICATION` expiries. Expiries detected by `BoundExecutors` in core are not counted. ([005-deadlines](../specs/005-deadlines/spec.md) DL-09) | The name follows `boundrail.context.violations`; counting at detection never counts an expiry twice; core has no registry (RT-09). |
| D55 | Only executors returned by `BoundExecutors.attached`, and MVC `Callable`s (EXE-09), refuse a task whose deadline has expired: futures fail with `DeadlineExceededException`, and a task from `execute` is passed to the thread's `UncaughtExceptionHandler`. Decorated Spring executors in attached mode carry the deadline but still run expired tasks, which the `BoundExecutors` Javadoc states. ([005-deadlines](../specs/005-deadlines/spec.md) EXE-08) | A `TaskDecorator` receives the `FutureTask` or `CompletableFuture` task Spring created, and cannot fail it; skipping it would leave the future hanging forever. Calling the handler directly keeps pool threads alive and behaves the same on executors that run tasks inline. |
| D56 | An MVC `Callable` (EXE-09) whose deadline has expired when it starts is refused by a `CallableProcessingInterceptor` whose `preProcess` only checks the deadline, binding and restoring no context, registered through a `WebMvcConfigurer`, after the application's `CallableProcessingInterceptor`s so they still run. The check needs a `RequestMappingHandlerAdapter` built by `WebMvcConfigurationSupport`, which the `BoundExecutors` Javadoc states. (planning gap G2; [005-deadlines](../specs/005-deadlines/spec.md) EXE-08) "Last" holds through configurer ordering: an application `WebMvcConfigurer` at the same lowest order that is processed after the library's auto-configuration registers its interceptors after the check. | Skipping the task in MVC's executor would leave the request waiting for a result that never comes, while a failing `preProcess` completes it with the exception, which DL-08 maps. A check-only interceptor leaves context handling to D39's re-entry. |
| D57 | Settled while planning 005: a remaining-milliseconds value longer than `wire.max-value-length` removes `Boundrail-Deadline-Ms` from the request, including an application-set one, with one `OVERSIZED_VALUE`. Timeout capping covers the call until the response headers arrive; a timeout while reading the body is the client's own exception. A trusted `Boundrail-Deadline-Ms` on several lines, one of them too long, is reported once as `INVALID_VALUE`, with no length check, as IN-02 does. DeadlinePropagationIT starts its own edge with `default-inbound=500ms`; the shared edge has no default. A blank `max-inbound` means its default, 30s, and a blank `default-inbound` means unset. (planning gaps G3 – G6; [005-deadlines](../specs/005-deadlines/spec.md) DL-03, DL-05, DL-07) | Leaving an application's header in place would let a child get a longer budget (§4.8). The cap covers the call until the headers arrive because the interceptor's call returns then; the body is read after it. One rule for repeated headers keeps DL-03 aligned with IN-02. A default on the shared edge would change the other suites' requests. |
| D58 | Settled while planning 006: the edge binds `AUTHORITY` only from an `AbstractOAuth2TokenAuthenticationToken` (JWT or opaque bearer token), as `Authority.of(getName(), getToken().getTokenValue(), getToken().getExpiresAt())`; `Authority` gains an optional expiry. No adapter other than `ContextAuthenticationFilter` binds it, but application code may, for example to send a service credential; a contributor that puts `AUTHORITY` gets `IllegalArgumentException`. ([006-identity](../specs/006-identity/spec.md) ID-02, ID-10) | Covers both bearer token types Spring Security verifies, with no extraction SPI. The exchange cache needs the source expiry, which the propagator can only see on the authority. Forbidding application bindings would break a deliberate service credential; a contributor is the wrong place to set authority. |
| D59 | Settled while planning 006: the initiator comes from a trusted header first, then a contributor, then `user:` + `getName()`. A derived value that breaks ID-07 leaves the initiator unbound with one `INVALID_VALUE`. AuthorityPropagationIT checks authority only at edge, the one secured service; orders checks the forwarded header and the initiator. ([006-identity](../specs/006-identity/spec.md) ID-08, IT-4.2) | Applications can choose their own initiator format; an invalid name is handled as an invalid claim is (IN-06). Securing orders would reject IT-4.7's literal service token and change the shared topology. |
| D60 | Amends D35: WIRE-04's length limit does not apply to the `Authorization` value the interceptor writes; WIRE-08 does, so an invalid value is not written and one `INVALID_VALUE` is reported. ([006-identity](../specs/006-identity/spec.md) ID-03; [003-http-trust](../specs/003-http-trust/spec.md) WIRE-04) | Bearer tokens routinely exceed the default limit of 256, so applying it would drop every forwarded JWT. The credential comes from verified authentication or the token endpoint, not the wire, yet a CR or LF in it must still never reach a header. |
| D61 | Settled while planning 006: the token exchange is an RFC 8693 form `POST` with `subject_token_type` access token and `audience` set to the service name, authenticated with HTTP Basic; `token-uri` (absolute `http` or `https`), `client-id` and `client-secret` are required with the `exchange` propagator. A 200 JSON response with a non-empty `access_token` is valid; `token_type` is not checked. Tokens are cached until 30 seconds before expiry, never beyond the source credential's expiry, in at most 10,000 entries with the oldest evicted first, keyed by a SHA-256 hash of the credential; a response without `expires_in` is not cached, and concurrent misses may both exchange. The exchange times out at the remaining deadline (`DeadlineExceededException`, `TIMEOUT`), or at a fixed 10 seconds with no deadline. ([006-identity](../specs/006-identity/spec.md) ID-04, ID-11, ID-12) | Every authorization server must support HTTP Basic (RFC 6749 §2.3.1), and the access-token type covers JWT and opaque tokens. Not caching without a known lifetime is the safe choice. A fixed bound keeps memory bounded without a property. The exchange is part of the call's budget. |
| D62 | Settled while planning 006: a failed token exchange throws the public `TokenExchangeException` and the request is not sent; its message names the audience and status only. ([006-identity](../specs/006-identity/spec.md) ID-12) | Sending without a credential would only fail downstream with a less precise error, and a new violation kind would change DIAG-01's fixed list. A dedicated type lets applications map the failure to a status. |
| D63 | Settled while planning 006: no library log entry or exception message contains a credential, exchanged token or the exchange client secret, and the `authority.exchange` properties record redacts the secret in `toString()`. ([006-identity](../specs/006-identity/spec.md) ID-06) | Exceptions end up in logs, and a record's default `toString()` prints every component. |
| D64 | Settled while planning 006 (planning questions Q1 – Q8): `Authority.toString()` does not show the expiry. A deadline that passed DL-06's check but has run out when the exchange starts throws `DeadlineExceededException` (`TIMEOUT`), counted once, with no exchange request and no call; a timeout during the exchange has the client's `HttpTimeoutException` as cause, as DL-07 does. A valid `expires_in` is any JSON number with a whole value of zero or more (`3600.0` and `1e3` pass), and one too large for a `long` is valid with the cache lifetime capped; strings, `null`, fractions and negatives are invalid. The response's `Content-Type` is not checked. `token-uri` needs a host. An interrupt during the exchange gives `TokenExchangeException` with the interrupt status restored. `ContextContributions.put(AUTHORITY, …)` checks the key before the value's type. ([006-identity](../specs/006-identity/spec.md) ID-02, ID-10, ID-11, ID-12) | The expiry is not sensitive but keeps the agreed format stable. An expired budget fails as a timeout without spending a request. Lenient number parsing accepts what JSON serializers emit for whole numbers, and authorization servers often send `application/json` with parameters or other types. A URI without a host cannot be called. Restoring the interrupt lets the caller see it. Checking the key first gives one error for one misuse. |
| D65 | The built-in `exchange` propagator parses token responses strictly with a Jackson 3 `ObjectMapper` (`tools.jackson.databind`, `compileOnly` in `boundrail-context-spring`), reading numbers exactly; selecting it without Jackson 3 on the classpath fails startup, even under lazy initialization. An `expires_in` read as NaN is invalid, and an infinite one counts as too large (valid, capped). A parse exception is never the cause or part of the message of `TokenExchangeException`. The library restores the interrupt status after an interrupted exchange. (spike T001, option B2; [006-identity](../specs/006-identity/spec.md) ID-11, ID-12) A response that repeats a key is invalid, through Jackson's `STRICT_DUPLICATE_DETECTION`, so no parser-dependent token is ever sent (Q9). | The T001 spike found that Spring Boot's fallback `BasicJsonParser` accepts malformed objects and can return a wrong `access_token` (trailing content, JSON `null` read as `"null"`, mangled escapes); a wrong credential must never be sent. Some parsers echo body content in their messages, and the body holds a token. The JDK `HttpClient` clears the interrupt status when it throws. |
| D66 | Settled while implementing 006 (T003 review): an `authority.exchange.token-uri` that carries userinfo fails startup when the built-in `exchange` propagator is selected, and the failure message never contains it. ([006-identity](../specs/006-identity/spec.md) ID-11) Also (T004 review): an OAuth2 authentication with an empty name still binds the authority with an empty subject, so its credential is forwarded (ID-02); a `null` name is treated as empty (ID-02, ID-08). | Client credentials belong in `client-id` and `client-secret`, which ID-06 redacts; a password in the URI would otherwise be printed by the properties' `toString()` and by `HttpRequest.newBuilder`'s error messages. |
| D67 | Settled while implementing 006 (T013 review), amends D65: an `expires_in` that the strict parser cannot represent as an exact decimal (an exponent beyond the `int` range, such as `1e2147483648`) is not a valid response, so ID-12's `TokenExchangeException` applies. A value too large for a `double`, such as `1e400`, is valid and capped; D65's "infinite" case cannot occur, because strict parsing rejects `Infinity`. Any `expires_in` at or above the representable range is capped without being expanded to its full integer value. ([006-identity](../specs/006-identity/spec.md) ID-11, ID-12) | A number with no exact value cannot be shown to be whole, so treating it as invalid is the safe reading and needs no special case. Expanding a short exponent such as `1e2000000000` to an integer would take unbounded time and memory on input from the token endpoint; any value past the cap gives the same lifetime anyway. |
| D68 | Settled after the 006 feature review (T016), amends D66: the `toString()` of the properties record that binds `authority.exchange`, and so of `BoundrailContextProperties`, never prints the userinfo of `token-uri`, whichever propagator is selected. A value that `java.net.URI` parses with a host and userinfo is printed with the userinfo replaced by `<redacted>` (`https://<redacted>@host/t`); any other value containing `@`, including one that does not parse, is printed as `<redacted>`; every other value is printed unchanged. ([006-identity](../specs/006-identity/spec.md) ID-06) | D66 only rejects userinfo when the built-in `exchange` propagator is selected; with `forward` or a replacing `AuthorityPropagator` bean the value binds unvalidated, so `toString()` printed the password. Keeping the host keeps the value useful for diagnosis; the same parse as ID-11's validation decides what counts as userinfo, and a value it cannot parse is hidden whole when it might hold one, the marker matching the client secret's. |
| D69 | Settled after the 006 feature review (T016), amends D67: an `expires_in` beyond the strict parser's limits is not a valid response, so ID-12's `TokenExchangeException` applies. That covers a number the parser cannot represent as an exact decimal (D67, `1e2147483648`) and a number longer than Jackson 3's default maximum number length (`StreamReadConstraints`, 1000 digits). ([006-identity](../specs/006-identity/spec.md) ID-11, ID-12) | Jackson 3 rejects such a number before the library sees it; any whole value that long is past the cap and would give the same lifetime, so raising the limit gains nothing and treating the rejection as invalid needs no special case. |
| D70 | Settled before planning 007 (was 007 Q1): `boundrail-context-kafka` depends on `boundrail-context-spring` as `api`, and reaches the effective policy, violation reporting, `violations.mode` and `wire.max-value-length` through public classes in `io.github.boundrail.context.spring.internal`, which no contract declares and CORE-03 does not cover. It has its own auto-configuration, ordered after the Spring module's and Spring Boot's Kafka auto-configuration, and binds `kafka.trust-headers` in its own properties record. ([007-kafka](../specs/007-kafka/spec.md) KAF-01 – KAF-04, Assumptions) | Kafka stays confined to `-kafka` (BLD-05), and one reporter per context keeps DIAG-03's rate limiter and DIAG-11's listener delivery intact, without widening the public API for one internal consumer. |
| D71 | Settled before planning 007 (was 007 Q2, Q3): the producer adapter covers every `KafkaTemplate` bean, subclasses included, through every send method, including `Message<?>` sends and `executeInTransaction`; not `execute(ProducerCallback)`, templates that are not beans, or producers created directly. In `fail` mode a missing required key throws `ContextViolationException` from the send call itself, and the record never reaches the producer. The planner's T001 spike picks the hook, one that composes with an application's own interceptor without reflection and can throw from the send call (Spring Kafka 4.1.1 calls the template's own interceptor in `doSend`; Kafka's `interceptor.classes` swallow exceptions), and its outcome goes back to the user. ([007-kafka](../specs/007-kafka/spec.md) KAF-01) | Templates are what applications send through, and a bean post-processor sees them all. Throwing from the call matches HTTP (DIAG-02), and a caller that ignores the returned future cannot miss it. |
| D72 | Settled before planning 007 (was 007 Q4, DESIGN Q4): record-mode listeners are scoped by an around-style `MethodInterceptor` added to the `ContainerProperties` advice chain of every `AbstractKafkaListenerContainerFactory` bean, Boot's included, outermost, composing with the application's advice through the chain's getter. Containers built in code, outside any factory bean, are not covered (KAF-06). The T001 spike confirms the mechanism, and its outcome goes back to the user. ([007-kafka](../specs/007-kafka/spec.md) KAF-03) | The advice chain wraps the listener invocation itself, so it works on the ScopedValue backend, covers every `@KafkaListener` and `@KafkaHandler` without AOP infrastructure, and has a getter, unlike `setContainerCustomizer`, so it never replaces the application's own configuration. |
| D73 | Settled before planning 007 (was 007 Q5): the container's handling of a record whose listener threw (`CommonErrorHandler`, recoverers such as `DeadLetterPublishingRecoverer`) also runs inside the record's snapshot; an asynchronous listener result (`CompletableFuture`, `Mono`) is not scoped beyond the invocation, which KAF-06 documents; and the listener scope is entered even when the record's snapshot is empty. If the T001 spike finds no around-style hook for error handling, the fallback is to document that it runs outside the scope (KAF-06), decided with the user. ([007-kafka](../specs/007-kafka/spec.md) KAF-02, KAF-03) | A dead-letter recoverer publishing outside the scope would fail on a required `MESSAGE` key in the default `fail` mode (POL-08 reads the context, not the copied headers), and the record would be retried again. Always entering the scope guarantees nothing leaks from the consumer thread, which matters more than the IN-01 optimisation. |
| D74 | Settled before planning 007 (was 007 Q6, Q7): inbound, a repeated wire header or a `null` value leaves the key unbound with one `INVALID_VALUE`; WIRE-04 is checked first, on the value decoded as UTF-8 with malformed sequences replaced, in UTF-16 code units, and malformed UTF-8 is then an `INVALID_VALUE`; on a trusted record, headers of keys that do not propagate to `MESSAGE`, `Boundrail-Deadline-Ms` and `Authorization` are ignored without a report. Outbound, WIRE-08 does not apply to record headers, WIRE-04 does; a header with the wire name that application code set, `Authorization` included, is left unchanged and the context value is not encoded; producer violations have an empty destination. ([007-kafka](../specs/007-kafka/spec.md) KAF-01, KAF-02, KAF-04) | The same rules as IN-02 and OUT-04, adapted to byte headers: a present header with no value is not absent, the length unit stays D35's, and a value that is both oversized and malformed is reported once, as WIRE-04 requires. CR and LF are harmless in record headers. `Destination` describes calls, and adding a topic to `Violation` would change the 003 contract. |
| D75 | Settled before planning 007 (was 007 Q8): an expired deadline neither fails nor changes a Kafka send; the deadline is neither propagated nor applied at `MESSAGE`, which KAF-06 documents. ([007-kafka](../specs/007-kafka/spec.md) KAF-01) | Matches §5.5's table, where `MESSAGE` has no apply effect, and §7: the event outlives the request. |
| D76 | Settled before planning 007 (was 007 Q9): `KafkaContext` is a bean the Kafka auto-configuration provides, with the instance method `forRecord(ConsumerRecord<?, ?>)`, which applies KAF-02 and KAF-04 and reports through the context; the draft's static method is replaced. ([007-kafka](../specs/007-kafka/spec.md) KAF-05) | The effective policy, the trust setting and violation reporting belong to one application context (RT-09), which a static method cannot see. |
| D77 | Settled before planning 007 (was 007 Q10 – Q12): the Kafka auto-configuration applies when Spring Kafka is on the classpath and the Spring module's auto-configuration is active; `kafka.trust-headers` is bound with Boot's boolean binding, and an unconvertible value fails startup even under lazy initialization; no switch turns the adapters off. IT-3.8 and IT-4.9 use Boot's auto-configured template and listener factory, the marker travels in the record's value, billing's listener waits until 4 seconds after the record's timestamp and records the record's header names, and "processing succeeds" means the listener returns normally with exactly one observation. KAF-06 makes the Javadoc of `KafkaContext` state the coverage limits. ([007-kafka](../specs/007-kafka/spec.md) KAF-04, KAF-06, IT-3.8, IT-4.9) | The same binding rules as `audit.enabled` (DIAG-06) and `deadline.map-exception` (DL-08), and no configurability that was not asked for (principle VIII). Waiting on the record's timestamp proves the delay without a call that would widen the scenario. Shipping the limits with the feature makes them reviewable when M6 closes, as ID-09 and EXE-07 did. |
| D78 | Settled while planning 007 (planning gaps Q5 – Q7), amends D70, D76 and D77: the Kafka adapters are active whenever Spring Kafka and `boundrail-context-spring` are on the classpath, both dependencies of `boundrail-context-kafka` (Spring Kafka as `implementation`, `-spring` as `api`), with no property to turn them off, now requirement text in KAF-04. D70's public classes in `io.github.boundrail.context.spring.internal` are public types, among them the interface `AdapterSupport`, and the properties record is `BoundrailKafkaProperties`. `KafkaContext.forRecord` builds its snapshot in temporary scopes (nested, D83), because core has no public snapshot factory; the returned snapshot is not current when the call returns, and scope listeners, such as the MDC bridge, may observe enter and exit pairs per call (count amended by D83). ([007-kafka](../specs/007-kafka/spec.md) KAF-04, KAF-05) | D77's activation conditions must be verifiable, so they belong in a requirement. An interface keeps the internal surface small and replaceable in tests. Adding a public snapshot factory to core would widen its contract for one helper; enter and exit pairs are harmless, because the listener's restorer runs before the call returns. |
| D79 | Settled after the 007 spike (T001, planning gaps Q1 – Q4), amends D71, D72 and D73. Producer (amends D71): the hook is a `ProducerPostProcessor` added to the `ProducerFactory` of each `KafkaTemplate` bean, so coverage is every send through a producer that factory creates, including `execute(ProducerCallback)`, templates that are not beans but share the factory, and producers created directly from it; not covered: a `ProducerFactory` that is not a `DefaultKafkaProducerFactory` (its `addPostProcessor` does nothing), and producers the factory created and cached before the hook was added. Listener scope (amends D72): advice the application adds to a factory's advice chain runs inside the library's scope; `RecordInterceptor`s (`intercept`, `success`, `failure`, `afterRecord`) run outside it; a container whose advice chain the application replaces, for example with a `ContainerCustomizer`, is not covered, and the Javadoc does not promise that a `@KafkaListener` container keeps the scope then. Error handling (amends D73): a decorator on each container's `CommonErrorHandler`, and on the `AfterRollbackProcessor` of transactional containers, installed before the containers start, runs `handleOne`, `handleRemaining` and `process` in the failed record's snapshot, rebuilt without reporting its violations again, or in the empty snapshot when rebuilding throws; a decorated `DefaultErrorHandler` is set only on non-transactional containers without one. Not covered: containers created or registered after startup, `handleOtherException` and the batch paths. A record whose decoding throws, with a required `MESSAGE` key in `fail` mode, therefore cannot be dead-lettered and is redelivered. ([007-kafka](../specs/007-kafka/spec.md) KAF-01, KAF-03, KAF-06) | The spike confirmed each mechanism on JDK 21 and JDK 25: the post-processor throws from every send method and `executeInTransaction`, composes with the template's own interceptor and needs no reflection; the advice chain reaches every factory-created container; without the error-handler decorator a dead-letter send failed in a loop, and with it the record reached the DLT with its context. A template's interceptor can only see sends through the template, while the factory sees every producer it makes. Reporting the rebuilt record's violations again would count them twice; binding the keys that decoded when rebuilding throws is behaviour the spec does not define, so the empty snapshot follows WIRE-05's propagate rule. |
| D80 | Settled while planning 007 (planning gap Q8), amends D78: the Kafka auto-configuration is conditional on the `AdapterSupport` bean, as D77 first said, so when the application excludes the Spring module's auto-configuration and no `AdapterSupport` bean exists, it backs off, applying none of KAF-01 – KAF-05, instead of failing startup. Otherwise the adapters stay active whenever Spring Kafka and `boundrail-context-spring` are on the classpath, with no property to turn them off. ([007-kafka](../specs/007-kafka/spec.md) KAF-04) | Excluding an auto-configuration is Spring Boot's own way to turn a library off; failing startup would punish that deliberate choice, and the Kafka adapters cannot work without the effective policy and violation reporting that the Spring module provides. |
| D81 | Settled while implementing 007 (accepted 2026-10-21): auto-configuration classes, such as `BoundrailKafkaAutoConfiguration` and `BoundrailContextAutoConfiguration`, are public but not part of any contract, so CORE-03 and the contracts' "no other public types" statements do not cover them. Sending the same `ProducerRecord` instance more than once is outside KAF-01: Kafka makes a record's headers read-only once it is sent, so a later send that adds a header may throw `IllegalStateException`, and when a codec's `encode` throws, the headers written before it may stay on the application's record; KAF-06 documents this. ([007-kafka](../specs/007-kafka/spec.md) KAF-01, KAF-06) | The contracts listed public types without naming the auto-configuration classes, which Spring Boot loads by name and applications only exclude; stating it removes the contradiction without a contract for them. Copying the record or its headers on every send to support re-sending would cost every send for a pattern Kafka itself does not support, and rolling back partly written headers would need the same copy. |
| D82 | Settled while implementing 007 (accepted 2026-10-25), narrows D73 and D79: when the error handling rebuilds a failed record's snapshot, only a `RuntimeException` falls back to the empty snapshot; an `Error` propagates unchanged and the error handling does not run. A record whose key or value fails deserialization reaches the error handling without a listener invocation, because the container throws `DeserializationException` before the listener and its advice run, so KAF-02 never builds or reports its snapshot and the rebuild reports nothing: its header violations are never reported, though its error handling still runs in its snapshot. `MessageListenerContainer` beans whose phase is lower than `AbstractMessageListenerContainer.DEFAULT_PHASE` can start before the error handling decorators are installed and keep the error handler they started with, so they are not covered; containers of the `KafkaListenerEndpointRegistry` are covered whatever their phase, because the registry starts them in its own phase, `DEFAULT_PHASE`; containers that are neither beans nor registered with the `KafkaListenerEndpointRegistry` are not covered either (amended 2026-10-26). KAF-06 documents the last two. ([007-kafka](../specs/007-kafka/spec.md) KAF-03, KAF-06) | Swallowing an `Error` such as `OutOfMemoryError` to run error handling would hide a failure the JVM may not survive. Reporting from the rebuild would report a record's violations twice whenever the listener did run, and telling the two cases apart is not worth it for records that already fail deserialization. A container's consumer caches its error handler when it is constructed at start, so a container that starts before the installer cannot be decorated afterwards. The installer runs at `DEFAULT_PHASE - 1`, but a bean in that same phase can start before it, because the lifecycle processor starts the beans of one phase in registration order and the auto-configured installer usually registers after the application's beans; so only a phase of `DEFAULT_PHASE` or higher is safe. |
| D83 | Settled while implementing 007 (plan gap Q9, accepted 2026-10-26), amends D78: `KafkaContext.forRecord` keeps building the record's snapshot in two nested scopes, entering `ContextSnapshot.EMPTY` and then the binding decoded from the record, so registered scope listeners, such as the MDC bridge, may observe more than one enter and exit pair per call; KAF-05 says they may observe enter and exit pairs per call, without a count. ([007-kafka](../specs/007-kafka/spec.md) KAF-05) | A single binding cannot give exactly the record's bindings when the caller's snapshot binds `DEADLINE`, because `without(DEADLINE)` is rejected (DL-11), and core has no public snapshot factory and stays unchanged in 007. Entering one scope only when the caller binds no deadline would save a pair in the common case at the cost of two code paths and a count that depends on the caller; the listener's restorer runs before the call returns, so the extra pair is harmless. |

### Open proposals

| # | Proposal | Why |
|---|---|---|
| P7 | Maven group and base package are `io.github.boundrail` and `io.github.boundrail.context`, unless the `boundrail.io` domain is acquired before the first release. | Verifiable on Maven Central through the GitHub org; changing packages after a release is a breaking change. |

---

## 17. Open questions

| # | Question | Current thinking | Resolve in |
|---|---|---|---|
| Q1 | Which HTTP request factories support per-request timeout capping in v0.1? | Resolved: D51 ([005-deadlines](../specs/005-deadlines/spec.md) DL-07). | Resolved in M4 |
| Q2 | Which built-in inbound trust verifiers should exist beyond CIDR? | Envoy XFCC / SPIFFE identity, trusted gateway header. | After v0.1 |
| Q3 | Should the Kafka consumer optionally start a fresh per-message processing deadline? | Useful, but not needed for v0.1. | After v0.1 |
| Q4 | How should Kafka listeners be scoped: container advice chain, or AOP around listener methods? | Resolved: D72, the `ContainerProperties` advice chain of every listener container factory bean, the library's advice outermost ([007-kafka](../specs/007-kafka/spec.md) KAF-03). Confirmed by the M6 spike (T001) and amended by D79. | Resolved in M6 |
| Q5 | Which tooling should publish to Maven Central? | Choose at release time. Known issue: T020a applies Gradle's built-in `maven-publish` to `boundrail-context-test` only, so its POM and module metadata can be generated and checked (TEST-01, D37). Gradle warns that a dependency on an unpublished project (`boundrail-context-core`, `boundrail-context-spring`) is deprecated and will fail in Gradle 10. Before any upgrade to Gradle 10, every published module needs a publication; until M7 the warning is accepted. Also, since 004 (TEST-04) the generated POM and module metadata of `boundrail-context-test` list `junit-jupiter-api` and `assertj-core` without a version, because the Boot BOM is only a `compileOnly` platform there; publishing needs `versionMapping { allVariants { fromResolutionResult() } }` or explicit versions so Maven consumers can resolve them. | M7 |
| Q6 | What may an inbound adapter log or report when a value fails to decode (WIRE-03, WIRE-05, IN-06), and what maximum length should it enforce on an inbound header value before decoding it? Codec `decode` messages echo the raw input (`Invalid long: "…"`, the JDK's `For input string: "…"`), which is untrusted (principle III), unbounded, may contain CR/LF, and may belong to a key that is not loggable. Header values are untrusted and can be large (Kafka headers have no size limit); a 4.5 KB language tag once overflowed the stack in the locale regex, fixed since with possessive quantifiers. | Resolved: D35 ([003-http-trust](../specs/003-http-trust/spec.md) WIRE-04, WIRE-05, IN-06, DIAG-09). | Resolved in M2 |
| Q7 | How should an HTTP client adapter handle a 3xx redirect that the HTTP client follows below the interceptor? The interceptor classifies each request once (OUT-01, OUT-02 in [003-http-trust](../specs/003-http-trust/spec.md)), so a request redirected to another host may carry the context headers written for the original destination without being classified again or stripped (WIRE-07). | Resolved: D52, a documented limitation in v0.1 ([005-deadlines](../specs/005-deadlines/spec.md) OUT-05); re-classifying redirects is left for after v0.1. | Resolved in M4 |
| Q8 | How should Spring MVC async processing (`DeferredResult`, `Callable`) keep the inbound context? `ContextInboundFilter` does not run again on async dispatches (IN-01 in [003-http-trust](../specs/003-http-trust/spec.md)), so the async dispatch runs without the context bound for the original request. | Resolved: D39 ([004-async-diagnostics](../specs/004-async-diagnostics/spec.md) IN-09, EXE-09). | Resolved in M3 |
| Q9 | Which Kafka adapter decisions remain open before M6 is planned? How `boundrail-context-kafka` reaches the Spring module's policy and violation reporting, which sends and listener containers are covered, where a `fail`-mode violation surfaces at send, what runs inside the listener scope (error handling, asynchronous results), how byte headers that repeat, are `null` or are not UTF-8 are read, how outbound headers are written, whether an expired deadline affects a send, the shape of `KafkaContext.forRecord` under RT-09, and how IT-3.8 and IT-4.9 run. | Resolved: D70 – D77 ([007-kafka](../specs/007-kafka/spec.md) KAF-01 – KAF-06, CORE-17). The M6 spike (T001) confirmed the mechanisms of D71, D72 and D73, and D79 records its outcome and limits. | Resolved in M6 |

---

## Appendix A. Glossary

| Term | Definition |
|---|---|
| Key | A `ContextKey<T>`: a name, a type and an optional default. Identity is by instance. |
| Snapshot | An immutable set of key/value bindings. Exactly one snapshot is *current* at any point of execution. |
| Scope | A lexical region during which a given snapshot is current. |
| Binding | A pending change (add, rebind, remove) that is applied for the duration of a scope. |
| Boundary | A point where work crosses from one execution context to another: `ATTACHED`, `DETACHED`, `INTERNAL_CALL`, `EXTERNAL_CALL` or `MESSAGE`. |
| Attached work | In-process work whose result the caller awaits. |
| Detached work | In-process work that may outlive the caller (fire-and-forget). |
| Destination | The classified target of an outbound call: `INTERNAL`, `EXTERNAL` or `UNKNOWN`, plus an optional service name. |
| Trusted caller | An inbound caller that the `InboundTrustVerifier` classifies as `TRUSTED_INTERNAL`. |
| Edge | A service that receives requests from untrusted callers. |
| Authority | Who is currently authorised: a subject and a credential. |
| Initiator | Who originally caused the work: an audit identifier. |
| Authority propagator | An `AuthorityPropagator`: decides which credential, if any, an internal call carries for a destination. |
| Token exchange | Trading the caller's credential for a token scoped to the destination service (RFC 8693), which the `exchange` propagator does. |
| Deadline | An instant on a monotonic clock after which the work is no longer useful. |
| Violation | A context rule broken at runtime. |
| Wire name | The HTTP header or Kafka header name that carries a key. |
| Codec | Converts a key's value to and from its wire string. |
| Adapter | A component that carries context across one boundary type. |
| Capture site | The first caller frame outside the library where a snapshot was created, recorded only when capture sites are on or sampled. |
| Scope listener | A `ScopeListener` hook in core that is told when any scope is entered, and whose restorer runs when it exits. |
| Executor audit | The startup report that lists every executor bean as `COVERED` (with its mode) or `GAP`. |
| Record-mode listener | A Kafka listener that receives one record per invocation, as opposed to a batch listener. |
| Listener scope | The scope opened around each record-mode listener invocation, holding exactly the context its record carried. |
