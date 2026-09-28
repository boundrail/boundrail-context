# Implementation Plan: Identity

**Branch**: `006-identity` | **Date**: 2026-09-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/006-identity/spec.md`

**Status**: Outline. Complete it with `/speckit-plan` (or the `planner` agent) when this feature starts.

## Summary

Authority is only ever established from verified authentication and never leaks, while the initiator provides a complete audit trail: binding
authority from Spring Security, forward and exchange propagators with a minimal RFC 8693 client and cache, respecting application-set
`Authorization`, deriving the initiator, and the redaction guarantees.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.x, Spring Security (OAuth2 resource server); JDK `HttpClient` for the token exchange

**Testing**: unit tests in `-spring`; AuthorityPropagationIT (IT-4.1 – IT-4.8) with the token-exchange stub

**Project Type**: library

**Constraints**: credentials never appear in logs, MDC, violation reports or `toString()`; cache keys never hold a credential in clear

## Constitution Check

| Principle | Status | Notes |
|---|---|---|
| III Secure by default | Gate | Authority only from verified authentication (ID-02); never detached, external, messages or logs (ID-06) |
| VIII Simplicity | Gate | Minimal exchange client: client credentials, audience, caching |
| VII Test-first | Gate | Enable IT-4.1 – IT-4.8 first |

## Scope and Approach (from ROADMAP M5)

- Binding authority from Spring Security's `Authentication`.
- `AuthorityPropagator` with forward and exchange modes, including a reference RFC 8693 client with token caching.
- Respecting an `Authorization` header set by application code.
- Deriving the initiator at the edge, and giving trusted headers precedence.
- The redaction guarantees.
- Documenting that detached work carries no authority.

## Project Structure

```text
specs/006-identity/{spec.md, plan.md, tasks.md, contracts/identity-api.md}

boundrail-context-core/src/main/java/io/github/boundrail/context/            # AuthorityPropagator
boundrail-context-spring/src/main/java/io/github/boundrail/context/spring/   # propagators, exchange client, authentication binding
integration-tests/src/test/java/io/github/boundrail/context/it/AuthorityPropagationIT.java
```

## Risks

The scope of the token exchange client. Keep it minimal (client credentials, audience, caching) and test it against the stub token endpoint.

## Complexity Tracking

None expected.
