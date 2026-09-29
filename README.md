# boundrail-context

> **`boundrail-context` defines what context means, where it may travel, and how deadlines behave across service boundaries.**
> Micrometer can move context. `boundrail-context` defines its contract.

`boundrail-context` is the context module of **Boundrail**, the parent framework for declared service contracts in Java.

> **Status:** pre-release. The design for v0.1 is agreed and development follows the milestones in [roadmap.md](docs/roadmap.md). There is no
> published artifact yet.

## Why

In a Spring microservice, request context such as tenant, user, locale, correlation identifiers and time budget has to cross thread pools, `@Async`
methods, scheduled jobs, outgoing HTTP calls, Kafka messages and other services. Hand-written MDC filters, TaskDecorators and interceptors fail in
predictable ways:

- **Context is silently lost** at an executor nobody decorated.
- **Context leaks** where it should not go, such as an internal header forwarded to a third-party API.
- **Context is forged** because a service trusts a header that a public client can set.
- **Deadlines do not travel**, so downstream services keep working after the caller gave up.

Micrometer and OpenTelemetry solve the mechanics of moving context. `boundrail-context` answers the application-level questions: what context exists,
where it came from, where it is allowed to go, and what happens if it disappears.

## What it provides

- **Typed context keys** with a propagation policy for every key at every kind of boundary.
- **Trust rules** for which incoming context is accepted and which context may leave.
- **Deadline budgets** that shrink as a request travels and can be shortened but never extended.
- **Identity** that separates the authority a call runs with from the initiator who caused the work.
- **Diagnostics** that turn lost context into a test failure instead of a production mystery.
- **Two runtime backends:** `ScopedValue` on Java 25+ and `ThreadLocal` on Java 21–24, selected automatically through a multi-release JAR.

## Modules

| Module | Contents |
|---|---|
| `boundrail-context-core` | Keys, snapshots, scopes, policy, codecs, destination classification, deadline and identity types, both backends; zero runtime dependencies |
| `boundrail-context-spring` | Spring Boot 4 auto-configuration: servlet filters, trust, RestClient/RestTemplate, executors, `@Scheduled`, MDC, audit, security |
| `boundrail-context-kafka` | Spring Kafka producer and consumer adapters |
| `boundrail-context-test` | JUnit Jupiter extension, assertions and a test trust verifier |

## Requirements

- Java 21 or later at runtime; building requires a JDK 25 toolchain.
- Spring Boot 4 (Spring Framework 7) for the Spring and Kafka modules.

## Documentation

- [design.md](docs/design.md): what the project is, why it exists, and every design decision.
- [roadmap.md](docs/roadmap.md): milestones from an empty repository to the v0.1 release.
- [specs/](specs/): normative requirements and acceptance scenarios, one Spec Kit feature per milestone.

## Licence

Apache License 2.0. See [LICENSE](LICENSE).
