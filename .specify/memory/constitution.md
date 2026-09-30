<!--
Sync Impact Report
- Version: 1.2.0 → 1.2.1 (PATCH: the Test names rule states that a `@ParameterizedTest` counts by its method's `@DisplayName`,
  not its invocation names; principle VII points to it)
- 1.1.2 → 1.2.0 (MINOR: principle VII adds approved TDD exceptions and debugging to root cause; the
  workflow adds per-task review and commits on the feature branch, following the superpowers skills test-driven-development,
  systematic-debugging, verification-before-completion and requesting-code-review)
- 1.1.1 → 1.1.2 (PATCH: Spec Kit reinstalled; the constitution is back at .specify/memory/constitution.md and the workflow names the
  /speckit-plan and /speckit-tasks commands again)
- 1.1.0 → 1.1.1: Spec Kit removed; constitution moved to docs/ (reverted by 1.1.2)
- 1.0.0 → 1.1.0: Specification Rules absorb the conventions of the removed docs/SPEC.md
- template → 1.0.0: initial ratification; principles I–VIII derived from docs/design.md §4 (frozen invariants), §13 and AGENTS.md
- Templates: plan-template.md "Constitution Check" gates resolve against the principles below; no template edits needed
-->

# boundrail-context Constitution

## Core Principles

### I. Context Is Immutable and Scope-Only

Context cannot be mutated. It can only be rebound for a lexical scope, or captured and explicitly re-entered. There is no `set()`. Leaving a
scope always restores the previous snapshot, including on exceptions. This keeps identical semantics across the ThreadLocal, ScopedValue and
any future Reactor backend. (DESIGN §4.1)

### II. Key, Policy and Adapter Are Separate; Remote Propagation Is Declared

A key is a typed semantic value; a policy decides where it may travel; an adapter carries it across one boundary type. Propagation is decided per
key and per boundary. In-process propagation is the default; remote propagation is denied unless the policy declares it. (DESIGN §4.2, §4.3)

### III. Secure by Default at Trust Boundaries

Every outbound call is classified before any context is written, and `UNKNOWN` is treated as `EXTERNAL`. Inbound context headers from untrusted
callers are ignored and reported, never bound. Authority (who is authorised) and initiator (who caused the work) are distinct; authority comes only
from verified authentication and never leaks to detached work, external calls, messages or logs. (DESIGN §4.4–§4.6)

### IV. Declared, Never Guessed

Attached versus detached work is declared per executor, defaulting to detached. A deadline shrinks as it travels, stops at detached boundaries, is
applied locally even where it is not propagated, and can be shortened but never extended by a child operation. (DESIGN §4.7, §4.8)

### V. Diagnostics Report Evidence, Not Guesses

Violation reports contain only facts the library observed. Diagnostics have an explicit cost model: cheap by default, detailed in development and
tests. (DESIGN §4.9)

### VI. Interoperate, Don't Replace

Micrometer, OpenTelemetry and Spring stay in charge of what they already do well. `boundrail-context` adds the contract on top. (DESIGN §4.10)

### VII. Spec-First, Test-First (NON-NEGOTIABLE)

- Behaviour exists only if a spec requirement describes it. A behaviour change starts as a spec change, then tests, then code.
- Acceptance scenarios are written before the feature, disabled with `@Disabled("Pending: Mx")`, and enabled when the feature starts.
- Tests are written and seen failing before the implementation (red, green, refactor). Test display names start with the IDs they verify
  (Test names under Specification Rules).
- Config-only tasks (build scripts, CI, resource files) may skip test-first only as TDD exceptions the user approves with the plan,
  each naming the check that verifies it.
- Failures are debugged to their root cause, with a failing test, before any fix. Three failed fixes on one problem make it a design
  question for the user.
- Nothing is reported as done, passing or fixed without a fresh run of the command that proves it.
- A feature is done only when all of its requirements are implemented and all of its scenarios are enabled and green on JDK 21 and JDK 25.

### VIII. Simplicity and Surgical Changes

The minimum code that satisfies the requirement IDs in scope. No speculative abstractions, configurability or work from later features. Every
changed line traces to the task at hand; unrelated problems are reported, not fixed. (AGENTS.md)

## Technical Constraints

- Minimum runtime Java 21; ScopedValue backend on Java 25+ through a multi-release JAR. Building requires a JDK 25 toolchain.
- `boundrail-context-core` has zero runtime dependencies and logs through `System.Logger`. Spring is confined to `-spring`, `-kafka` and `-test`;
  Kafka to `-kafka`. Spring Security and Micrometer are optional.
- Spring modules target Spring Boot 4.x.
- Backend-sensitive and acceptance tests run in `integration-tests` against the packaged JARs, on JDK 21, JDK 25, and JDK 25 with the ThreadLocal
  backend forced. Kafka tests use Testcontainers; Kafka and HTTP clients are never mocked.
- Apache-2.0 licence.

## Specification Rules

- **Where things live.** Each feature's requirements, acceptance scenarios and configuration properties live in `specs/###-feature-name/spec.md`;
  `###` is the development order. Signatures live in the feature's `contracts/`, as signatures only; the base package is
  `io.github.boundrail.context` (proposal P7 in design.md). The shared test topology is defined in `specs/001-foundation/spec.md`, the glossary in
  design.md Appendix A, and traceability by milestone in roadmap.md.
- **Precedence.** The specs are the source of truth for behaviour. design.md explains why; if the two disagree, the specs win and design.md gets
  fixed.
- **Normative language.** SHALL / SHALL NOT: absolute requirement. SHOULD: recommended; any deviation must be justified in the pull request. MAY:
  optional.
- **EARS patterns.** Ubiquitous: "The library SHALL …"; event-driven: "WHEN …, the library SHALL …"; unwanted behaviour: "IF …, THEN the library
  SHALL …"; optional feature: "WHERE …, the library SHALL …".
- **Identifiers.** Every requirement has a stable ID (`AREA-NN`) and a milestone tag such as `[M2]`. IDs are never reused or renumbered, including
  when a requirement moves between feature folders. A removed requirement stays in place, marked *Withdrawn*. Scenario IDs are `IT-n.m` or `RC-n`.
- **Test names.** Test display names start with the IDs they verify, for example
  `@DisplayName("IT-2.5 forged tenant header from public caller is ignored")` or
  `@DisplayName("CORE-07 binding restores previous snapshot after exception")`. For a `@ParameterizedTest`, the display name that counts is
  the method's `@DisplayName`; invocation names such as `[1] "THREAD-LOCAL"` need not start with the ID.
- Markdown lines are at most 150 characters (the `markdown-conventions` skill).

## Development Workflow

1. One feature at a time, in number order, on a branch named after its folder (e.g. `002-core-runtime`).
2. `spec.md` is agreed first. `plan.md` and `tasks.md` are produced when the feature starts (`/speckit-plan`, `/speckit-tasks`, or the
   `planner` agent), and must pass the Constitution Check.
3. The user approves the spec, plan, tasks and TDD exceptions before any implementation starts.
4. Implement task by task, each one red-green cycle. Every task is reviewed against its requirement IDs, this constitution and
   AGENTS.md, then committed on the feature branch; the whole feature is reviewed again before it closes. Pushing and merging need the
   user.
5. `./gradlew check` must be green, run fresh, before a feature is closed.

## Governance

This constitution supersedes other practices. Principles I–VI restate the frozen invariants in design.md §4; amending one requires a new entry
in the design.md decision log and a matching update here. Versioning follows semantic versioning: MAJOR for removing or redefining a principle,
MINOR for adding one, PATCH for wording. Every plan's Constitution Check and every review verifies compliance.

**Version**: 1.2.1 | **Ratified**: 2026-09-28 | **Last Amended**: 2026-09-30
