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

For every key, the policy states which boundaries the key crosses, whether it is *required*, and whether it may be *logged*. Remote boundaries also
need a *codec* and a *wire name*. Keys not registered in the policy stay in-process, crossing `ATTACHED` and `DETACHED` only.

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
anything. Reports are rate-limited log entries, plus a Micrometer counter when Micrometer is present.

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
one listener. Listeners and the capture-site mode are the only state that applies to the whole JVM. Everything else, such as the policy and violation
reporting, belongs to one Spring application context.

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
| `boundrail-context-test` | JUnit 5 extension, assertions, test trust verifier | yes |
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
only), JUnit 5, AssertJ and Testcontainers. CI runs on GitHub Actions with both JDKs installed.

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

### Open proposals

| # | Proposal | Why |
|---|---|---|
| P7 | Maven group and base package are `io.github.boundrail` and `io.github.boundrail.context`, unless the `boundrail.io` domain is acquired before the first release. | Verifiable on Maven Central through the GitHub org; changing packages after a release is a breaking change. |

---

## 17. Open questions

| # | Question | Current thinking | Resolve in |
|---|---|---|---|
| Q1 | Which HTTP request factories support per-request timeout capping in v0.1? | JDK `HttpClient` first; others log a warning at startup. | M4 spike |
| Q2 | Which built-in inbound trust verifiers should exist beyond CIDR? | Envoy XFCC / SPIFFE identity, trusted gateway header. | After v0.1 |
| Q3 | Should the Kafka consumer optionally start a fresh per-message processing deadline? | Useful, but not needed for v0.1. | After v0.1 |
| Q4 | How should Kafka listeners be scoped: container advice chain, or AOP around listener methods? | Must be around-style. Decide in a spike. | M6 spike |
| Q5 | Which tooling should publish to Maven Central? | Choose at release time. | M7 |

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
| Deadline | An instant on a monotonic clock after which the work is no longer useful. |
| Violation | A context rule broken at runtime. |
| Wire name | The HTTP header or Kafka header name that carries a key. |
| Codec | Converts a key's value to and from its wire string. |
| Adapter | A component that carries context across one boundary type. |
