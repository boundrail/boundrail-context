# Implementation Plan: Async Work and Diagnostics

**Branch**: `004-async-diagnostics` | **Date**: 2026-09-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-async-diagnostics/spec.md`

**Status**: Outline. Complete it with `/speckit-plan` (or the `planner` agent) when this feature starts.

## Summary

Context survives in-process async boundaries, and when it doesn't, the failure is loud and precise: executor modes and decoration, `@Async` and
`@Scheduled` support, the scope-listener hook and the MDC bridge on it, capture sites, evidence-only violations, the startup audit, and the test
extension with assertions.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.x task execution and scheduling; SLF4J (Spring module only); Micrometer optional

**Testing**: unit tests in core and `-spring`; RC-9 in RuntimeConformanceIT; ContextPropagationIT

**Project Type**: library

**Constraints**: scope listeners and capture-site mode are the only additions to JVM-wide state (RT-09); stack walking only when capture sites are
on or sampled

## Constitution Check

| Principle | Status | Notes |
|---|---|---|
| I Scope-only | Gate | Decorators and `BoundExecutors` open scopes around tasks; MDC is restored, never cleared |
| IV Declared, never guessed | Gate | Executor mode is declared, default detached (EXE-01) |
| V Evidence-only diagnostics | Gate | DIAG-04; capture-site cost model (DIAG-05) |
| VI Interoperate | Gate | Compose with existing decorators, e.g. Micrometer's (EXE-03) |

## Scope and Approach (from ROADMAP M3)

- Executor modes, and auto-decoration of Spring task executors and schedulers, composing with existing decorators.
- `BoundExecutors`, `@Async` support, and the `@Scheduled` system context.
- The `ScopeListener` hook in core, and the MDC bridge built on it.
- Capture-site recording (off, on or sampled), evidence-only violation reports, and correlation between thread names and executors.
- The startup audit, with an optional fail-on-gap setting.
- The `@BoundrailTest` extension and context assertions.

## Project Structure

```text
specs/004-async-diagnostics/{spec.md, plan.md, tasks.md, contracts/}

boundrail-context-core/src/main/java/io/github/boundrail/context/            # ScopeListener, BoundExecutors, capture sites
boundrail-context-spring/src/main/java/io/github/boundrail/context/spring/   # decorators, audit, MDC bridge, @Scheduled
boundrail-context-test/src/main/java/io/github/boundrail/context/test/       # @BoundrailTest, ContextAssertions
integration-tests/src/test/java/io/github/boundrail/context/it/ContextPropagationIT.java
```

## Risks

Composing with task decorators that Spring Boot or Micrometer already registered. IT-1.1 must pass with Micrometer's context-propagating decorator
present.

## Complexity Tracking

None expected.
