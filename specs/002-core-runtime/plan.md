# Implementation Plan: Core and Runtimes

**Branch**: `002-core-runtime` | **Date**: 2026-09-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-core-runtime/spec.md`

**Status**: Outline. Complete it with `/speckit-plan` (or the `planner` agent) when this feature starts; add `research.md` and `data-model.md`
then if needed.

## Summary

The scope-only context model in plain Java: keys, snapshots, bindings, capture and re-entry; the ThreadLocal and ScopedValue backends with
automatic selection and a ThreadLocal override; codecs; the policy builder with validation and filtering; and the built-in deadline, authority and
initiator value types with their local rules.

## Technical Context

**Language/Version**: Java 21 (main), Java 25 (`java25` source set for the ScopedValue backend)

**Primary Dependencies**: none at runtime (BLD-03); JUnit 5 and AssertJ for tests

**Testing**: unit tests in `boundrail-context-core`; RuntimeConformanceIT in `integration-tests` on all three JDK tasks

**Project Type**: library

**Performance Goals**: `Context.capture()` cost independent of the number of keys (CORE-09)

**Constraints**: no JVM-wide state beyond RT-09; no reflection on Java version strings (RT-02)

## Constitution Check

| Principle | Status | Notes |
|---|---|---|
| I Scope-only | Gate | No setter or in-place mutation in the public API (CORE-06) |
| II Separation, declared propagation | Gate | Keys carry no policy; unregistered keys stay in-process (POL-02) |
| IV Deadlines only shrink | Gate | DL-10, DL-11 |
| VII Test-first | Gate | Enable RC-1 – RC-8 before implementing |
| VIII Simplicity | Gate | No Spring, no adapters in this feature |

## Scope and Approach (from ROADMAP M1)

- The core API: `ContextKey`, `ContextSnapshot`, `Context` and `ContextBinding`, plus `MissingContextException`.
- The ThreadLocal and ScopedValue backends, backend selection, and the ThreadLocal override. Selection goes through one class present in the base
  JAR and in `META-INF/versions/25/` (RT-02).
- Codecs: string, long, UUID and Locale.
- The `ContextPolicy` builder, validation, and snapshot filtering by boundary.
- The value types and built-in keys `Deadline`, `Authority` and `Initiator`, with their default policies, including the rule that a nested scope
  can shorten a deadline but never extend it.

## Project Structure

```text
specs/002-core-runtime/
├── spec.md
├── plan.md
├── tasks.md
└── contracts/core-api.md

boundrail-context-core/src/main/java/io/github/boundrail/context/        # API and ThreadLocal backend
boundrail-context-core/src/main/java25/io/github/boundrail/context/      # ScopedValue backend selection
boundrail-context-core/src/test/java/io/github/boundrail/context/        # unit tests
integration-tests/src/test/java/io/github/boundrail/context/it/RuntimeConformanceIT.java
```

## Risks

Backend differences in exception handling and nested scopes. The conformance suite exists to catch exactly this.

## Complexity Tracking

None expected.
