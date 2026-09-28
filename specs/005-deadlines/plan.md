# Implementation Plan: Deadlines

**Branch**: `005-deadlines` | **Date**: 2026-09-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/005-deadlines/spec.md`

**Status**: Outline. Complete it with `/speckit-plan` (or the `planner` agent) when this feature starts.

## Summary

A request's time budget travels, shrinks and is enforced, and it stops at detached boundaries: inbound acceptance with cap and default, rejection of
expired requests, the remaining-time header, per-request timeout capping, fail-fast before sending, exception mapping, the metric, and attached
executors refusing expired tasks.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.x; JDK `HttpClient` request factory; Micrometer optional

**Testing**: unit tests in `-spring`; DeadlinePropagationIT (IT-3.1 – IT-3.7)

**Project Type**: library

**Constraints**: deadlines use the monotonic clock only; the wire format is remaining milliseconds, never an absolute time

## Constitution Check

| Principle | Status | Notes |
|---|---|---|
| III Secure by default | Gate | Only trusted callers set deadlines (DL-03) |
| IV Deadlines have semantics | Gate | Not propagated to detached work or external calls, but applied as a timeout cap everywhere |
| VII Test-first | Gate | Enable IT-3.1 – IT-3.7 first |

## Scope and Approach (from ROADMAP M4)

- Inbound deadline handling: acceptance from trusted callers, the cap, the edge default, and rejecting expired requests.
- Outbound deadline handling: the remaining-time header, capping per-request timeouts, and failing fast before sending.
- Mapping `DeadlineExceededException` to 504, and the deadline metric.
- Attached executors refusing to start expired tasks.

## Project Structure

```text
specs/005-deadlines/{spec.md, plan.md, tasks.md}

boundrail-context-spring/src/main/java/io/github/boundrail/context/spring/   # inbound filter, interceptor, exception mapping
boundrail-context-core/src/main/java/io/github/boundrail/context/            # attached-executor expiry check
integration-tests/src/test/java/io/github/boundrail/context/it/DeadlinePropagationIT.java
```

## Risks

Per-request timeout support varies by HTTP request factory (DESIGN Q1). Run a spike at the start of the feature. Supporting the JDK `HttpClient`
factory is sufficient for v0.1.

## Complexity Tracking

None expected.
