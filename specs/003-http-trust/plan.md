# Implementation Plan: HTTP Boundaries and Trust

**Branch**: `003-http-trust` | **Date**: 2026-09-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-http-trust/spec.md`

**Status**: Outline. Complete it with `/speckit-plan` (or the `planner` agent) when this feature starts.

## Summary

Context crosses HTTP boundaries only where the policy and the trust rules allow: Spring Boot auto-configuration, policy bean merging, the inbound
filter with trust verification, the authentication filter with contributors, the outbound interceptor with destination classification, header
stripping, wire limits, and violation reporting.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.x (Spring MVC, `RestClient`, `RestTemplate`); Spring Security and Micrometer optional

**Testing**: unit and slice tests in `-spring`; TrustBoundaryIT in `integration-tests`

**Project Type**: library (Spring Boot auto-configuration)

**Constraints**: destination classification stays in core with no HTTP dependency (DEST-01); every adapter is around-style

## Constitution Check

| Principle | Status | Notes |
|---|---|---|
| II Declared propagation | Gate | Only keys that propagate to `INTERNAL_CALL` are written (OUT-04) |
| III Secure by default | Gate | Default verifier trusts nobody (IN-03); `UNKNOWN` = `EXTERNAL` (DEST-03); stripping (WIRE-07) |
| V Evidence-only diagnostics | Gate | Violations carry observed facts only |
| VII Test-first | Gate | Enable IT-2.1 – IT-2.9 first |

## Scope and Approach (from ROADMAP M2)

- Spring Boot auto-configuration skeleton and policy bean merging.
- The inbound filter, the `InboundTrustVerifier` (defaulting to trust nobody, with CIDR support), and `ContextAuthenticationFilter` with
  contributors, including `JwtClaimContributor`.
- The outbound interceptor for `RestClient` and `RestTemplate`.
- The transport-neutral `DestinationClassifier` in core (DEST), with per-client declarations and host patterns wired up by the Spring module.
- Header stripping on external calls, wire value limits, and decode errors.
- Basic violation reporting (log and metric), including `fail` and `report` modes for missing required keys.
- `TestInboundTrustVerifier` in the test module.

## Project Structure

```text
specs/003-http-trust/{spec.md, plan.md, tasks.md, contracts/http-api.md}

boundrail-context-core/src/main/java/io/github/boundrail/context/       # Destination, classifier, Violation
boundrail-context-spring/src/main/java/io/github/boundrail/context/spring/
boundrail-context-test/src/main/java/io/github/boundrail/context/test/
integration-tests/src/test/java/io/github/boundrail/context/it/TrustBoundaryIT.java
```

## Risks

Filter ordering relative to Spring Security. IT-2.5 covers the capture-before-authentication case.

## Complexity Tracking

None expected.
