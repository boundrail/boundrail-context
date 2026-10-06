---
name: boundrail-conventions
description: Project rules for Boundrail Context that generic Java/Spring advice gets wrong — module dependency limits, the Java 21 + java25 multi-release source sets, scope-only context, System.Logger in core, MDC restore (never clear), and test rules. Use when writing, reviewing or planning any code or build change in this repo.
---

# Boundrail Context conventions

Boundrail Context is a **library**, not an application. The source of truth is `specs/` and `docs/`:

- `specs/###-feature-name/spec.md` — numbered requirements (`BLD-04`, `RT-02`, `LOG-01`, …), one
  feature per milestone, with its configuration properties. Cite the ID when implementing or
  testing one. The shared test topology is in `specs/001-foundation/spec.md`; spec conventions
  are in `.specify/memory/constitution.md`.
- `docs/design.md` — §4 lists frozen invariants. Changing one needs a decision-log entry (§16);
  flag it to the user instead of working around it.
- `docs/roadmap.md` — milestones (M0–M7). Don't build ahead of the current milestone.

When this skill and a generic skill (`java`, `spring-boot`) disagree, this one wins.

## Modules and dependencies

| Module | Dependency limits |
|---|---|
| `boundrail-context-core` | JDK only — zero runtime dependencies (BLD-03) |
| `boundrail-context-spring` | Spring Boot 4; Spring Security and Micrometer only as `compileOnly` + conditional auto-config (BLD-05) |
| `boundrail-context-kafka` | the only module allowed Kafka dependencies (BLD-05) |
| `boundrail-context-test` | `boundrail-context-spring` as `api`; `spring-web` and `jakarta.servlet-api` `compileOnly` (TEST-01, D37) |
| `integration-tests`, `examples/propagation-demo` | not published |

Spring dependencies stay out of core entirely.

Base package: `io.github.boundrail.context` (proposal P7 — still open until M7).

## Java levels and the multi-release JAR

- Main source sets compile with `--release 21`. Only core's `src/main/java25` compiles with
  `--release 25` and is packaged under `META-INF/versions/25/` (BLD-04).
- `ScopedValue` appears **only** in `src/main/java25`. `StructuredTaskScope` is not used (still
  preview in 25). In base code, `ThreadLocal` is the correct backend, not a smell.
- Backend selection goes through a class present in both variants — never reflection on
  `java.version` (RT-02). `-Dboundrail.context.backend=thread-local` forces ThreadLocal (RT-04).
- Multi-release classes only load from a JAR, so `integration-tests` runs against packaged
  JARs on JDK 21, JDK 25, and JDK 25 with the thread-local override (BLD-06).

## Context model

- Context is immutable and scope-only: there is no `set()`. Rebind for a lexical scope or
  capture and re-enter (DESIGN §4.1).
- The ThreadLocal backend restores the previous snapshot in `finally` and calls `remove()` when
  the outermost scope exits, so pooled threads carry no residue (RT-03).
- Propagation is per key and per boundary; remote propagation is denied unless declared.
  Unknown destinations are external. Inbound context headers from untrusted callers are
  reported, never bound (§4.3–4.5).
- Executors are `DETACHED` unless declared `ATTACHED`. Only Spring's task executor and scheduler
  types are auto-decorated; plain `ExecutorService` beans are wrapped explicitly (EXE-01, EXE-04).

## Logging

- **Core** logs through `java.lang.System.Logger`. No SLF4J, no Logback, no Lombok `@Slf4j`.
- **Spring module** mirrors loggable keys into SLF4J's MDC on scope entry and **restores the
  previous value of each of those keys** on exit (LOG-01). Never `MDC.clear()` or
  `setContextMap(...)` — both clobber entries the application owns.

  ```java
  Map<String, String> previous = new HashMap<>();
  loggable.forEach((name, value) -> {
      previous.put(name, MDC.get(name));   // null = key was absent
      MDC.put(name, value);
  });
  try {
      body.run();
  } finally {
      previous.forEach((name, old) -> { if (old == null) MDC.remove(name); else MDC.put(name, old); });
  }
  ```
- `AUTHORITY` is never logged, put in the MDC, or included in diagnostics. `DEADLINE` is never
  put in the MDC either, even if the policy marks it loggable (LOG-02).
- Violation reports are rate-limited and contain only observed facts (§4.9).

## Tests

- JUnit 6 + AssertJ. Auto-configuration is tested with `ApplicationContextRunner` (see
  `spring-boot`).
- Integration tests use Testcontainers for Kafka and SHALL NOT mock Kafka or HTTP clients
  (BLD-07).
- Scenarios for later milestones exist from M0, compile, and are disabled with
  `@Disabled("Pending: Mx")`. A milestone is done when all its scenarios are enabled and pass
  (BLD-10).
- The gate is `./gradlew check` (BLD-08).
