---
description: "Task list for 001-foundation"
---

# Tasks: Foundation

**Input**: Design documents from `/specs/001-foundation/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md)

**Tests**: Required (constitution principle VII). Each task is one red-green cycle: its failing test and the code that makes it pass,
committed and reviewed together. Config-only tasks tagged `[TDD-EXCEPTION: …]` skip test-first and are verified by the named check;
they need the user's approval with the plan (see the TDD Exceptions table in [plan.md](plan.md)).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: The user story the task belongs to (US1–US4)
- **[TDD-EXCEPTION: check]**: approved config-only task and the check that verifies it

## Phase 1: Setup

**Purpose**: Gradle skeleton

- [x] T001 [TDD-EXCEPTION: `./gradlew --version` runs] Add the Gradle wrapper (`gradlew`, `gradle/wrapper/`); the root `.gitignore`
      already covers `build/` and `.gradle/`
- [x] T002 [TDD-EXCEPTION: `./gradlew projects` lists the six projects of BLD-02] Create `gradle/libs.versions.toml` (JUnit 6, AssertJ,
      Testcontainers, Spring Boot 4.x, Spring Kafka, Spring Security), `build-logic/settings.gradle.kts`, `build-logic/build.gradle.kts`
      (`kotlin-dsl`), and `settings.gradle.kts` with `includeBuild("build-logic")` and the six projects (BLD-01, BLD-02)
- [x] T003 [TDD-EXCEPTION: `./gradlew build` passes, and `:boundrail-context-core:dependencies --configuration runtimeClasspath` lists no
      dependencies] Create `build-logic/src/main/kotlin/boundrail.java-conventions.gradle.kts` (JDK 25 toolchain, `options.release = 21`,
      JUnit platform) and the `build.gradle.kts` of `boundrail-context-core` (no dependencies), `-spring` (Spring Boot 4; Security and
      Micrometer `compileOnly`), `-kafka`, `-test` and `examples/propagation-demo` (BLD-03, BLD-05, BLD-12)

**Checkpoint**: `./gradlew build` succeeds with empty projects.

---

## Phase 2: Foundational

**Purpose**: The integration-test project, which every story's tests run in

- [x] T004 [TDD-EXCEPTION: `./gradlew check --dry-run` lists the three integration test tasks] Create
      `build-logic/src/main/kotlin/boundrail.integration-tests.gradle.kts` (tasks `integrationTestJdk21`, `integrationTestJdk25`,
      `integrationTestJdk25ThreadLocal` with system property `boundrail.context.backend=thread-local` and filter `*RuntimeConformanceIT`;
      each with `-Djdk.net.hosts.file`; `check` depends on all three), `integration-tests/build.gradle.kts` applying it with the four
      library projects on the test runtime classpath, and `integration-tests/src/test/resources/hosts` mapping `edge.svc.test`,
      `orders.svc.test`, `billing.svc.test`, `token-exchange.svc.test`, `partner.example.test` and `localhost` to `127.0.0.1` (BLD-06)

**Checkpoint**: `./gradlew check` runs three (empty) integration test tasks.

---

## Phase 3: User Story 1 - Multi-release build (Priority: P1) 🎯 MVP

**Goal**: A core JAR with a Java 25 variant, proven by a test.

**Independent Test**: `./gradlew :integration-tests:integrationTestJdk21 :integration-tests:integrationTestJdk25 --tests '*BuildSmokeIT'`

- [x] T005 [US1] Red: write `integration-tests/src/test/java/io/github/boundrail/context/it/BuildSmokeIT.java` with
      `@DisplayName("BLD-11 versioned marker class loads on JDK 25 only")`, asserting `RuntimeVersionMarker.version()` is 25 when
      `Runtime.version().feature() >= 25` and 21 otherwise, and see it fail. Green: create
      `build-logic/src/main/kotlin/boundrail.multi-release.gradle.kts` (`java25` source set with `release = 25`, output packaged
      `into("META-INF/versions/25")`, manifest `Multi-Release: true`), apply it in `boundrail-context-core/build.gradle.kts`, and create
      `RuntimeVersionMarker` in `boundrail-context-core/src/main/java/io/github/boundrail/context/internal/` returning 21 and its
      `src/main/java25/` variant returning 25. Green on both JDKs; `jar tf` shows `META-INF/versions/25/` (BLD-04, BLD-11)

**Checkpoint**: BLD-04 and BLD-11 satisfied.

---

## Phase 4: User Story 2 - Integration tests against packaged JARs (Priority: P1)

**Goal**: The three test tasks run against JARs on the right launchers.

**Independent Test**: `./gradlew check`; the smoke test passes in the JDK 21 and JDK 25 tasks.

- [x] T006 [US2] Red: add to `BuildSmokeIT` a test `BLD-06 library classes load from packaged JARs` (core's classes come from a `.jar`)
      and a test `BLD-06 runs on the JDK its task declares` (the running JVM matches the version in the task's name, passed as the
      `boundrail.it.task` system property), and see the JDK test fail. Green: each integration test task passes its name. Prove both tests
      can fail: a class directory ahead of the core JAR fails the first, a task registered or launched with the wrong JDK fails the second
      (BLD-06)

**Checkpoint**: BLD-06 satisfied.

---

## Phase 5: User Story 3 - Executable specification skeleton (Priority: P2)

**Goal**: Every later scenario exists, compiles and is disabled; the topology starts.

**Independent Test**: The test report lists every scenario ID from `specs/002`–`007` as skipped with `Pending: Mx`.

- [x] T007 [US3] Red: write `TopologySmokeIT` (enabled), which starts the topology and calls each service once through its hosts-file
      name, and see it fail. Green: create in `topology/` the `ObservedContext` probe (per-service, per-request `Map<String, String>`
      observations), `TestJwts` (per-run signing key; user `alice` with claim `tenant=acme`), minimal Spring Boot applications `EdgeApp`,
      `OrdersApp`, `BillingApp`, `PartnerApp` (records received headers) and `TokenExchangeApp` (stub endpoint, counts calls), and
      `Topology`, a JUnit extension that starts the Testcontainers Kafka broker and the five contexts on random ports (BLD-07)
- [x] T008 [P] [US3] Write `RuntimeConformanceIT` with RC-1 – RC-8 (`Pending: M1`) and RC-9 (`Pending: M3`), and remove the temporary
      `isFailOnNoMatchingTests = false` from `boundrail.integration-tests.gradle.kts` (added in T004), so the thread-local run fails if the
      suite goes missing
- [x] T009 [P] [US3] Write `TrustBoundaryIT` with IT-2.1 – IT-2.9 (`Pending: M2`)
- [x] T010 [P] [US3] Write `ContextPropagationIT` with IT-1.1 – IT-1.8 (`Pending: M3`)
- [x] T011 [P] [US3] Write `DeadlinePropagationIT` with IT-3.1 – IT-3.7 (`Pending: M4`) and IT-3.8 (`Pending: M6`)
- [ ] T012 [P] [US3] Write `AuthorityPropagationIT` with IT-4.1 – IT-4.8 (`Pending: M5`) and IT-4.9 (`Pending: M6`)

Each scenario method: `@Test`, `@Disabled("Pending: Mx")`, `@DisplayName("<ID> <title>")`, and the scenario text from its `specs/###/spec.md` as a
comment. Bodies are written when the feature enables them, test-first. T008 – T012 add only disabled tests, so they have no red step; each is
verified by the test report listing its scenarios as skipped with the right reason (BLD-10).

**Checkpoint**: BLD-07 and BLD-10 satisfied; all 43 scenarios are skipped with the right reason and nothing fails.

---

## Phase 6: User Story 4 - CI and licence (Priority: P3)

**Goal**: Every push and pull request is checked.

**Independent Test**: A pull request shows a green `check` job.

- [ ] T013 [US4] [TDD-EXCEPTION: `./gradlew check` green locally; the workflow's first run on GitHub, after the user approves a push]
      Create `.github/workflows/ci.yml`: on push and pull request, `actions/setup-java` with JDK 21 and 25, `gradle/actions/setup-gradle`,
      run `./gradlew check`. Confirm `LICENSE` is Apache-2.0 at the root (BLD-08, BLD-09)

---

## Phase 7: Close

- [ ] T014 Verify BLD-01 – BLD-12 one by one against the build, and run `./gradlew check` fresh; with the CI run green, this closes the
      feature (leader step 5)

## Dependencies & Execution Order

- Phase 1 → Phase 2 → US1 → US2. US3 needs Phase 2 only and can run in parallel with US1/US2. US4 can start after Phase 1.
- Within US3, T007 precedes T008 – T012, which are independent of each other.
- Every task is committed and reviewed on its own, on branch `001-foundation`.
