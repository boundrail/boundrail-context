# Implementation Plan: Kafka

**Branch**: `007-kafka` | **Date**: 2026-09-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/007-kafka/spec.md`

**Status**: Outline. Complete it with `/speckit-plan` (or the `planner` agent) when this feature starts.

## Summary

Context travels with messages under detached semantics: a producer adapter that writes headers on the calling thread, a consumer adapter that
scopes each record-mode listener invocation around-style, header trust defaults, and the `KafkaContext.forRecord` helper for batch listeners.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Kafka (Spring Boot 4.x)

**Testing**: unit tests in `-kafka`; IT-3.8 and IT-4.9 against Testcontainers Kafka (no mocked clients, BLD-07)

**Project Type**: library

**Constraints**: around-style scoping only (KAF-03); authority and deadline never read from messages (KAF-04)

## Constitution Check

| Principle | Status | Notes |
|---|---|---|
| I Scope-only | Gate | Around-style listener scope; works on the ScopedValue backend |
| III Secure by default | Gate | Authority and deadline excluded from messages regardless of configuration |
| IV Declared semantics | Gate | `MESSAGE` is detached: no deadline |
| VII Test-first | Gate | Enable IT-3.8 and IT-4.9 first |

## Scope and Approach (from ROADMAP M6)

- A producer adapter that writes headers on the calling thread.
- A consumer adapter that scopes each record's listener invocation. Run a spike on DESIGN Q4 first: the container advice chain versus AOP around
  listener methods.
- Header trust defaults, and the `KafkaContext.forRecord` helper for batch listeners.

## Project Structure

```text
specs/007-kafka/{spec.md, plan.md, tasks.md, contracts/kafka-api.md}

boundrail-context-kafka/src/main/java/io/github/boundrail/context/kafka/
integration-tests/src/test/java/io/github/boundrail/context/it/{DeadlinePropagationIT, AuthorityPropagationIT}.java
```

## Risks

Scoping listeners in a way that works with the ScopedValue backend. Set/restore interceptors alone are not acceptable (KAF-03).

## Complexity Tracking

None expected.
