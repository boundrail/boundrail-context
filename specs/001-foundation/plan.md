# Implementation Plan: Foundation

**Branch**: `001-foundation` | **Date**: 2026-09-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-foundation/spec.md`

## Summary

Set up an empty but complete repository: a Gradle Kotlin DSL build with a version catalog and `build-logic` convention plugins, the six projects,
a multi-release core JAR (release 21 plus a `java25` source set for release 25), an `integration-tests` project that runs against the packaged JARs
on three JDK launchers, the test topology skeleton, every later scenario as a disabled test, and GitHub Actions CI.

## Technical Context

**Language/Version**: Java 21 (main), Java 25 (`java25` source set); build requires a JDK 25 toolchain

**Primary Dependencies**: Gradle (Kotlin DSL, toolchains, version catalog); Spring Boot 4.x, Spring Kafka, Spring Security OAuth2 resource server
(test topology only); latest stable versions at implementation time, pinned in `gradle/libs.versions.toml`

**Storage**: N/A

**Testing**: JUnit 6 (managed by the Spring Boot BOM), AssertJ, Testcontainers (Kafka), Spring Boot test support

**Target Platform**: JVM 21+ (library)

**Project Type**: library (multi-project Gradle build)

**Performance Goals**: N/A for this feature

**Constraints**: core has zero runtime dependencies (BLD-03); integration tests use packaged JARs (BLD-06); no mocks of Kafka or HTTP clients
(BLD-07)

**Scale/Scope**: 6 projects, 5 test suites, 43 disabled scenarios

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|---|---|---|
| I–VI (design invariants) | Pass | No library behaviour in this feature |
| VII Spec-first, test-first | Pass, with approved exceptions | Scenarios are written before any feature; the smoke test and topology smoke test are written before the code they prove; config-only tasks are TDD exceptions (below) |
| VIII Simplicity | Pass | Only the conventions the build needs; no publishing setup until 008-release |
| Technical constraints | Pass | Toolchains, MR-JAR, packaged-JAR tests and dependency confinement are what this feature builds |

## Research Notes

- **Packaged JARs on the runtime classpath.** For a `java-library` project dependency, Gradle resolves the `runtimeElements` variant, whose artifact
  is the JAR, so `testRuntimeClasspath` contains JARs by default. The smoke test (BLD-11) is the guard: it fails on JDK 25 if classes are ever
  loaded from directories.
- **One launcher per test task.** Each `Test` task sets `javaLauncher` from `javaToolchains.launcherFor { languageVersion = … }`. The thread-local
  task adds `systemProperty("boundrail.context.backend", "thread-local")` and filters to `*RuntimeConformanceIT`.
- **Multi-release packaging.** A `java25` source set compiled with `options.release = 25`, whose output is added to the core `jar` task
  `into("META-INF/versions/25")`, plus the manifest attribute `Multi-Release: true`.
- **Disabled scenarios before the API exists.** Scenario methods carry their display name, `@Disabled("Pending: Mx")` and the Given/When/Then as a
  comment; bodies are written when the feature enables them. `ObservedContext` records observations as `Map<String, String>`, so the topology
  skeleton does not depend on the core API.
- **Hosts file.** `integration-tests/src/test/resources/hosts` maps the topology names and `localhost` to `127.0.0.1`; each test task passes
  `-Djdk.net.hosts.file=<absolute path>`.

## TDD Exceptions

Config-only tasks with no behaviour to test first. They need the user's approval with this plan; each is verified by the check named.

| Task | What | Verified by |
|---|---|---|
| T001 | Gradle wrapper | `./gradlew --version` runs |
| T002 | Version catalog, `build-logic` and settings | `./gradlew projects` lists the six projects of BLD-02 |
| T003 | Java conventions and module build files | `./gradlew build` passes; core's `runtimeClasspath` has no dependencies (BLD-03) |
| T004 | Integration-test tasks, integration-tests project, hosts file | `./gradlew check --dry-run` lists the three tasks; hosts resolution proven by T007 |
| T006 | Classpath check for packaged JARs | Fails with a class directory put on the classpath by hand, passes on the real build |
| T013 | CI workflow, licence | `./gradlew check` locally; the workflow's first run on GitHub after the user approves a push |

The multi-release build (T005) and the topology (T007) are not exceptions: a failing smoke test comes first.

## Project Structure

### Documentation (this feature)

```text
specs/001-foundation/
├── spec.md
├── plan.md      # this file
└── tasks.md
```

### Source Code (repository root)

```text
settings.gradle.kts                 # includeBuild("build-logic"), the six projects
gradle/libs.versions.toml
gradle/wrapper/                     # Gradle wrapper
build-logic/
├── settings.gradle.kts
├── build.gradle.kts                # kotlin-dsl
└── src/main/kotlin/
    ├── boundrail.java-conventions.gradle.kts       # toolchain 25, release 21, JUnit platform
    ├── boundrail.multi-release.gradle.kts          # java25 source set, jar packaging, manifest
    └── boundrail.integration-tests.gradle.kts      # the three JDK test tasks, hosts file
boundrail-context-core/
├── build.gradle.kts
├── src/main/java/io/github/boundrail/context/internal/RuntimeVersionMarker.java
└── src/main/java25/io/github/boundrail/context/internal/RuntimeVersionMarker.java
boundrail-context-spring/build.gradle.kts
boundrail-context-kafka/build.gradle.kts
boundrail-context-test/build.gradle.kts
integration-tests/
├── build.gradle.kts
└── src/test/
    ├── java/io/github/boundrail/context/it/
    │   ├── BuildSmokeIT.java
    │   ├── RuntimeConformanceIT.java
    │   ├── ContextPropagationIT.java               # IT-1
    │   ├── TrustBoundaryIT.java                    # IT-2
    │   ├── DeadlinePropagationIT.java              # IT-3
    │   ├── AuthorityPropagationIT.java             # IT-4
    │   └── topology/                               # Topology extension, five services, ObservedContext, TestJwts
    └── resources/hosts
examples/propagation-demo/build.gradle.kts
.github/workflows/ci.yml
LICENSE                              # already present
```

**Structure Decision**: One Gradle multi-project build with an included `build-logic` build, as BLD-01 and BLD-02 require. All acceptance tests
live in `integration-tests`, the only project that consumes the packaged JARs.

## Complexity Tracking

No constitution violations to justify.
