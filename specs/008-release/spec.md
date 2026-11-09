# Feature Specification: v0.1 Release

**Feature Branch**: `008-release`

**Created**: 2026-09-28

**Status**: Implemented

**Milestone**: M7

**Input**: Roadmap milestone [M7](../../docs/roadmap.md#m7--v01-release) and design.md §15 (the three
demos). The REL requirements restate the roadmap's scope and exit criteria as testable requirements.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A developer can evaluate the library in minutes (Priority: P1)

An experienced Spring developer reads the README, runs the demo, and sees the three guarantees working: context loss caught by a test, a tenant
stripped from an external call, and a deadline shrinking across hops while a Kafka event is unaffected.

**Why this priority**: The release exists to put the library in front of developers.

**Independent Test**: Follow the README quick start and the demo instructions on a clean machine with Docker.

**Acceptance Scenarios**:

1. **Given** a clean checkout and Docker, **When** a developer runs the demo as the README describes, **Then** all three demos run and show the
   documented output. *(REL-01, REL-02)*
2. **Given** the README's "Guarantees" section, **When** a reader follows each link, **Then** it leads to the acceptance test that proves it.
   *(REL-01)*

---

### User Story 2 - The library is published (Priority: P1)

Version 0.1.0 of the four published projects is available on Maven Central under the final group and package names.

**Why this priority**: Without a published artifact there is nothing for developers to use.

**Independent Test**: A fresh Gradle project resolves `0.1.0` from Maven Central.

**Acceptance Scenarios**:

1. **Given** the release, **When** a new project depends on the four artifacts at `0.1.0`, **Then** they resolve from Maven Central. *(REL-04)*

---

### Edge Cases

- Central does not register `io.github.boundrail` automatically, because it names an organization: the request needs manual
  verification, and publishing waits for it (REL-04, DESIGN D84).

## Requirements *(mandatory)*

**REL-01** `[M7]` The README SHALL contain the headline from design.md §1, a quick start, usage examples ordered from simple to advanced, a
"Guarantees" section that links each guarantee to the acceptance test that proves it, the instructions to run the demo, and a sample of the
output of the three demos (DESIGN D89).

**REL-02** `[M7]` `examples/propagation-demo` SHALL run with Docker Compose and one script, and SHALL show the three demos of design.md §15, in a
compact output of a few lines per demo. It SHALL use in-repo project dependencies and SHALL NOT be published (DESIGN D87).

**REL-03** `[M7]` The user-facing configuration reference SHALL be a committed `docs/configuration.md`, generated from the Configuration tables in
the feature specs (today 002–007; every spec that has one), and `./gradlew check` SHALL fail when it is stale (DESIGN D88).

**REL-04** `[M7]` 0.1.0 of the four published projects SHALL be published to Maven Central under the group `io.github.boundrail`, through the
Central Portal, with the tooling DESIGN D85 names (DESIGN D84, D85). The `developers` section of each POM SHALL give the maintainer as `jordi1st`
with the URL `https://github.com/jordi1st`, and SHALL NOT contain an email address. Each POM SHALL also give the project's name, its description from
the Contents cell of design.md §12, the GitHub repository as its URL and SCM, and the Apache-2.0 licence. Each project SHALL be published with a
sources JAR and a Javadoc JAR; core's sources JAR SHALL mirror its multi-release layout, with the Java 25 sources under `META-INF/versions/25/`,
and its Javadoc covers the main sources only.

### Key Entities

- **Release artifacts**: the four published projects at version 0.1.0.
- **Demo**: `examples/propagation-demo`, the three scenarios of design.md §15.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 0.1.0 is on Maven Central.
- **SC-002**: A developer new to the project runs the demo from the README in under 10 minutes.

## Assumptions

- Features 001–007 are complete and every acceptance suite is green.
