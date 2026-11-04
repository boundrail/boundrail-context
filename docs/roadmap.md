# boundrail-context — Roadmap

> **Status:** plan for v0.1 · **Last updated:** 4 November 2026
> **Companion documents:** [design.md](design.md) (what and why) · [specs/](../specs/) (normative requirements and acceptance
> tests)

This roadmap takes `boundrail-context` from an empty repository to a public v0.1 release and a go/no-go decision based on developer feedback.

---

## How milestones work

Each milestone is one Spec Kit feature under [`specs/`](../specs/), numbered in development order (M0 is `001-foundation`, M1 is
`002-core-runtime`, and so on). Every requirement carries a milestone tag such as `[M2]`, and every acceptance scenario belongs to one milestone.

- All acceptance scenarios are written in **M0**, compile, and are disabled with `@Disabled("Pending: Mx")`.
- A milestone is **done** when every requirement tagged with it is implemented and every scenario tagged with it is enabled and green on JDK 21 and
  JDK 25.
- There are no dates. Sizes are relative (S, M, L), and milestones are ordered by dependency.

### Working agreement for spec-driven development with Claude Code

1. Work one milestone at a time, on its own branch or a small series of pull requests.
2. Start every task by reading the milestone's `specs/###-feature-name/spec.md`, then complete its `plan.md` and `tasks.md`.
3. When behaviour needs to change, **change the spec first**, then the tests, then the code.
4. Put requirement or scenario IDs at the start of test display names, for example `IT-2.5 …` or `CORE-07 …`.
5. When a proposal (P-numbers) or open question (Q-numbers) in design.md is resolved, record the outcome in design.md's decision log.

---

## Overview

| Milestone | Feature | Theme | Size | Done when | Status |
|---|---|---|---|---|---|
| **M0** | [001](../specs/001-foundation/) | Foundation | M | Build, CI and docs are in place; all acceptance suites exist but are disabled | Done |
| **M1** | [002](../specs/002-core-runtime/) | Core and runtimes | M | Runtime conformance suite is green on both backends | Done |
| **M2** | [003](../specs/003-http-trust/) | HTTP boundaries and trust | L | IT-2 is green | Done |
| **M3** | [004](../specs/004-async-diagnostics/) | Async work and diagnostics | L | IT-1 is green | Done |
| **M4** | [005](../specs/005-deadlines/) | Deadlines | M | IT-3 is green, except the messaging scenario | Done |
| **M5** | [006](../specs/006-identity/) | Identity | M | IT-4 is green, except the messaging scenario | Done |
| **M6** | [007](../specs/007-kafka/) | Kafka | M | All acceptance suites are fully green | Done |
| **M7** | [008](../specs/008-release/) | v0.1 release and validation | M | 0.1.0 is on Maven Central and the go/no-go decision is recorded | Not started |

```
M0 ─► M1 ─► M2 ─► M3 ─► M4 ─┐
                     │      ├─► M6 ─► M7
                     └► M5 ─┘
```

M5 depends on M2's HTTP filters and on M3's executors and MDC bridge (IT-4.5, IT-4.6), so it can run in parallel with M4 if that helps. M6 needs both
M4 and M5, because its scenarios assert that the deadline is absent and the initiator is present in consumers.

### Traceability

| Milestone | Feature | Requirements | Scenarios |
|---|---|---|---|
| M0 | 001-foundation | BLD-01 – BLD-12 | build smoke test (BLD-11); all suites written and disabled |
| M1 | 002-core-runtime | CORE-01 – CORE-12, RT-01 – RT-06, POL-01 – POL-06, POL-09, WIRE-03, DL-01 – DL-02, DL-10 – DL-11, ID-01, ID-07 | RC-1 – RC-8 |
| M2 | 003-http-trust | CORE-13, POL-07 – POL-08, POL-10, DEST-01 – DEST-04, WIRE-01 – WIRE-02, WIRE-04 – WIRE-08, IN-01 – IN-08, OUT-01 – OUT-04, DIAG-01 – DIAG-03, DIAG-08 – DIAG-09, TEST-01 | IT-2.1 – IT-2.9 |
| M3 | 004-async-diagnostics | CORE-14, RT-07 – RT-09, IN-09, EXE-01 – EXE-07, EXE-09 – EXE-10, DIAG-04 – DIAG-07, DIAG-10 – DIAG-12, LOG-01 – LOG-02, TEST-02 – TEST-04 | RC-9, IT-1.1 – IT-1.9 |
| M4 | 005-deadlines | CORE-15, DL-03 – DL-09, DL-12, OUT-05, EXE-08 | IT-3.1 – IT-3.7 |
| M5 | 006-identity | CORE-16, ID-02 – ID-06, ID-08 – ID-12 | IT-4.1 – IT-4.8 |
| M6 | 007-kafka | CORE-17, KAF-01 – KAF-06 | IT-3.8, IT-4.9 |
| M7 | 008-release | REL-01 – REL-05 | none |

---

## M0 — Foundation

**Feature:** [specs/001-foundation](../specs/001-foundation/spec.md)

**Goal:** a repository where the specification is executable before any feature exists.

**Scope**

- Create the repository `boundrail/boundrail-context` with the Apache 2.0 licence.
- Set up the Gradle build: Kotlin DSL, a version catalog, and convention plugins in `build-logic`.
- Create the six projects defined in BLD-02.
- Set up the multi-release JAR in core: the `java25` source set, the manifest entry, and a trivial marker class that exists in both the base and the
  versioned variant.
- Make `integration-tests` consume the packaged JARs, with `integrationTestJdk21`, `integrationTestJdk25` and `integrationTestJdk25ThreadLocal` tasks.
- Add GitHub Actions CI that installs JDK 21 and 25 and runs `./gradlew check`.
- Put design.md and roadmap.md under `docs/`, the feature specs under `specs/`, and add an `AGENTS.md` that points Claude Code at them and states the
  working agreement.
- Build the test topology skeleton: the five test services, the test hosts file, Testcontainers Kafka, and test JWT signing
  ([Test Topology](../specs/001-foundation/spec.md#test-topology)).
- Write the runtime conformance suite and all four acceptance suites, with every scenario disabled.

**Exit criteria**

- `./gradlew check` is green in CI.
- BLD-01 to BLD-12 are satisfied.
- The build smoke test (BLD-11) proves the JDK 25 run loads the versioned marker class from the JAR and the JDK 21 run does not.

**Risk.** Multi-release JARs and toolchain plumbing are fiddly. Doing them first means no logic depends on them until they are proven.

---

## M1 — Core and runtimes

**Feature:** [specs/002-core-runtime](../specs/002-core-runtime/spec.md)

**Goal:** the scope-only context model works in plain Java, identically on both backends.

**Scope**

- The core API: `ContextKey`, `ContextSnapshot`, `Context` and `ContextBinding`, plus `MissingContextException`.
- The ThreadLocal and ScopedValue backends, backend selection, and the ThreadLocal override.
- Codecs: string, long, UUID and Locale.
- The `ContextPolicy` builder, validation, and snapshot filtering by boundary.
- The value types and built-in keys `Deadline`, `Authority` and `Initiator`, with their default policies, including the rule that a nested scope can
  shorten a deadline but never extend it.

**Exit criteria**

- All requirements tagged `[M1]` (CORE, RT-01 to RT-06, POL-01 to POL-06, POL-09, WIRE-03, DL-01, DL-02, DL-10, DL-11, ID-01 and ID-07) are
  implemented.
- RuntimeConformanceIT is green on JDK 21, on JDK 25, and on JDK 25 with the ThreadLocal backend forced.

**Risk.** Backend differences in exception handling and nested scopes. The conformance suite exists to catch exactly this.

---

## M2 — HTTP boundaries and trust

**Feature:** [specs/003-http-trust](../specs/003-http-trust/spec.md)

**Goal:** context crosses HTTP boundaries only where the policy and the trust rules allow.

**Scope**

- Spring Boot auto-configuration skeleton and policy bean merging; `ContextPolicy.keys()` in core.
- The inbound filter, the `InboundTrustVerifier` (defaulting to trust nobody, with CIDR support), and `ContextAuthenticationFilter` with contributors,
  including `JwtClaimContributor`.
- The outbound interceptor for `RestClient` and `RestTemplate`.
- The transport-neutral `DestinationClassifier` in core (DEST), with per-client declarations and host patterns wired up by the Spring module.
- Header stripping on external calls, wire value limits, and decode errors, reported without the values (DESIGN D35).
- Basic violation reporting (log and metric), including `fail` and `report` modes for missing required keys.
- `TestInboundTrustVerifier` in the test module.

**Exit criteria**

- All requirements tagged `[M2]` are implemented.
- IT-2 (TrustBoundaryIT) is fully green.

**Risk.** Filter ordering relative to Spring Security. IT-2.5 covers the capture-before-authentication case.

---

## M3 — Async work and diagnostics

**Feature:** [specs/004-async-diagnostics](../specs/004-async-diagnostics/spec.md)

**Goal:** context survives in-process async boundaries, and when it doesn't, the failure is loud and precise.

**Scope**

- Executor modes, and auto-decoration of Spring task executors and schedulers, composing with existing decorators.
- `BoundExecutors`, `@Async` support, and the `@Scheduled` system context.
- The `ScopeListener` hook in core, and the MDC bridge built on it.
- JVM-wide state limited to the snapshot slot, the scope listeners and the capture-site mode (RT-09).
- Capture-site recording (off, on or sampled), evidence-only violation reports, and correlation between thread names and executors.
- The startup audit, with an optional fail-on-gap setting.
- The `@BoundrailTest` extension and context assertions.
- Keeping the inbound context across Spring MVC async processing (`DeferredResult`, `Callable`), the known M2 limitation (DESIGN Q8, D39).

**Exit criteria**

- All requirements tagged `[M3]` are implemented.
- IT-1 (ContextPropagationIT) is fully green.

**Risk.** Composing with task decorators that Spring Boot or Micrometer already registered. Scenario IT-1.1 must pass with Micrometer's
context-propagating decorator present.

---

## M4 — Deadlines

**Feature:** [specs/005-deadlines](../specs/005-deadlines/spec.md)

**Goal:** a request's time budget travels, shrinks and is enforced, and it stops at detached boundaries.

**Scope**

- Inbound deadline handling: acceptance from trusted callers, the cap, the edge default, and rejecting expired requests.
- Outbound deadline handling: the remaining-time header, capping per-request timeouts, and failing fast before sending.
- Mapping `DeadlineExceededException` to 504, and the deadline metric.
- Attached executors refusing to start expired tasks.

**Exit criteria**

- All requirements tagged `[M4]` are implemented.
- IT-3 is green, except IT-3.8 (messaging).

**Risk.** Per-request timeout support varies by HTTP request factory. Supporting the JDK `HttpClient` factory is sufficient for v0.1 (D51, was Q1);
run a spike at the start of the milestone to confirm how its per-request timeout is applied. Redirects the request factory follows stay a
documented limitation in v0.1 (D52, was Q7).

---

## M5 — Identity

**Feature:** [specs/006-identity](../specs/006-identity/spec.md)

**Goal:** authority is only ever established from verified authentication and never leaks, while the initiator provides a complete audit trail.

**Scope**

- Binding authority from Spring Security's `Authentication`.
- `AuthorityPropagator` with forward and exchange modes, including a reference RFC 8693 client with token caching.
- Respecting an `Authorization` header set by application code.
- Deriving the initiator at the edge, and giving trusted headers precedence.
- The redaction guarantees.
- Documenting that detached work carries no authority.

**Exit criteria**

- All requirements tagged `[M5]` are implemented.
- IT-4 is green, except IT-4.9 (messaging).

**Risk.** The scope of the token exchange client. Keep it minimal (client credentials, audience, caching) and test it against the stub token endpoint.

---

## M6 — Kafka

**Feature:** [specs/007-kafka](../specs/007-kafka/spec.md)

**Goal:** context travels with messages under detached semantics.

**Scope**

- A producer adapter that writes headers on the calling thread.
- A consumer adapter that scopes each record's listener invocation through the listener container factories' advice chain (D72, was Q4).
  The T001 spike confirmed it, the `ProducerPostProcessor` on each template bean's `ProducerFactory` (D71), and the error-handler and
  after-rollback decorators (D73); D79 records its outcome and the limits KAF-06 documents.
- Header trust defaults (D74, D77), and the `KafkaContext` bean whose `forRecord` serves batch listeners (D76).
- The Javadoc of `KafkaContext` states the coverage limits (KAF-06).

**Exit criteria**

- All requirements tagged `[M6]` are implemented.
- IT-3.8 and IT-4.9 are green, which makes every suite fully green.

**Risk.** Scoping listeners in a way that works with the ScopedValue backend. Set/restore interceptors alone are not acceptable (KAF-03).

---

## M7 — v0.1 release and validation

**Feature:** [specs/008-release](../specs/008-release/spec.md)

**Goal:** a public release good enough to put in front of experienced Spring developers, and a recorded decision about what happens next.

**Scope**

- A README with the headline, a quick start, a "Guarantees" section that links to the acceptance tests, and a GIF of the three demos.
- `examples/propagation-demo`, runnable with Docker Compose.
- A configuration reference generated from, or checked against, the Configuration tables in the feature specs.
- Resolve proposal P7 (group and package) and Q5 (publishing tooling), then publish 0.1.0 to Maven Central.
- Run the validation plan (design.md §15) with 5–10 experienced Spring developers and record their reactions.

**Exit criteria**

- 0.1.0 is on Maven Central.
- A written go/no-go decision is recorded in design.md's decision log: continue to v0.2 (with named themes), change positioning, or stop.

---

## Beyond v0.1

These are candidates, ordered by expected value. Re-rank them after the M7 validation.

1. **Boundrail core integration.** Failure contracts consume the deadline and context. This is the reason the module exists.
2. **Interop bridges** to Micrometer context-propagation and OpenTelemetry.
3. **More inbound trust verifiers**: service-mesh identity (Envoy XFCC, SPIFFE) and a trusted gateway header.
4. **A Reactor backend and WebClient support.**
5. **Database query timeouts derived from the deadline** (JDBC and JPA).
6. **An Actuator endpoint** exposing the policy, the audit and violation counts.
7. **More adapters**: Feign, gRPC, RabbitMQ and JMS.
8. **Kafka extras**: automatic batch-listener support and an optional per-message processing deadline.
9. **Signed context envelopes** for environments without mesh identity.
10. **A transit-time allowance for deadlines.**
11. **A `boundrail-bom`**, once there are several Boundrail modules.
12. **Java 17 support**, only if users ask for it.
