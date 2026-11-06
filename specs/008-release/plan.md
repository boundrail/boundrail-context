# Implementation Plan: v0.1 Release

**Branch**: `008-release` | **Date**: 2026-09-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/008-release/spec.md`

**Status**: Outline. Complete it with `/speckit-plan` (or the `planner` agent) when this feature starts.

## Summary

A public release good enough to put in front of experienced Spring developers.

## Technical Context

**Primary Dependencies**: the `com.vanniktech.maven.publish` plugin (DESIGN D85); Docker Compose for the demo

**Testing**: the configuration-reference check (REL-03) runs in `./gradlew check`; the demo is verified by hand from the README

**Project Type**: library release

## Constitution Check

| Principle | Status | Notes |
|---|---|---|
| VII Spec-first | Gate | README guarantees link to existing acceptance tests; no new behaviour |
| Technical constraints | Gate | Apache-2.0 licence and POM metadata on every published artifact; no email address in a POM (REL-04) |

## Scope and Approach (from ROADMAP M7)

- A README with the headline, a quick start, usage examples from simple to advanced, a "Guarantees" section that links to the acceptance tests,
  and a GIF of the three demos.
- `examples/propagation-demo`, runnable with Docker Compose.
- A configuration reference generated from, or checked against, the Configuration tables in the feature specs.
- Publish 0.1.0 to Maven Central under `io.github.boundrail` (D84), with the tooling D85 names.

## Project Structure

```text
specs/008-release/{spec.md, plan.md, tasks.md}

README.md
examples/propagation-demo/
build-logic/src/main/kotlin/        # publishing conventions
```

## Risks

The group and package are settled (DESIGN D84), but Central must verify the `io.github.boundrail` namespace by hand, and publishing waits for it.
Signing and the upload were not exercised in the spike, and Gradle 9.8.0 is above the plugin's stated compatibility (DESIGN D85).

## Complexity Tracking

None expected.
