# Implementation Plan: v0.1 Release and Validation

**Branch**: `008-release` | **Date**: 2026-09-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/008-release/spec.md`

**Status**: Outline. Complete it with `/speckit-plan` (or the `planner` agent) when this feature starts.

## Summary

A public release good enough to put in front of experienced Spring developers, and a recorded decision about what happens next.

## Technical Context

**Primary Dependencies**: Maven Central publishing tooling (DESIGN Q5, chosen in this feature); Docker Compose for the demo

**Testing**: the configuration-reference check (REL-03) runs in `./gradlew check`; the demo is verified by hand from the README

**Project Type**: library release

## Constitution Check

| Principle | Status | Notes |
|---|---|---|
| VII Spec-first | Gate | README guarantees link to existing acceptance tests; no new behaviour |
| Technical constraints | Gate | Apache-2.0 licence and POM metadata on every published artifact |

## Scope and Approach (from ROADMAP M7)

- A README with the headline, a quick start, a "Guarantees" section that links to the acceptance tests, and a GIF of the three demos.
- `examples/propagation-demo`, runnable with Docker Compose.
- A configuration reference generated from, or checked against, the Configuration tables in the feature specs.
- Resolve proposal P7 (group and package) and Q5 (publishing tooling), then publish 0.1.0 to Maven Central.
- Run the validation plan (design.md §15) with 5–10 experienced Spring developers and record their reactions.

## Project Structure

```text
specs/008-release/{spec.md, plan.md, tasks.md}

README.md
examples/propagation-demo/
build-logic/src/main/kotlin/        # publishing conventions
docs/design.md                      # P7, Q5 and go/no-go decisions
```

## Risks

Changing the group or package after the first release is a breaking change, so P7 must be settled before publishing.

## Complexity Tracking

None expected.
