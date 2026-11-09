---
description: "Task list for 008-release"
---

# Tasks: v0.1 Release

**Input**: Design documents from `/specs/008-release/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md); 001 – 007 merged, `main` at 3ac09e1. Before T001, the user has approved the spec, plan,
tasks, TDD exceptions and guard test, and the uncommitted spec, plan, tasks, design and roadmap edits are committed on `008-release`.

**Tests**: Required (constitution principle VII). Each agent task is one red-green cycle: its failing test, with the behaviour it pins down, and the
minimum code that makes it pass, committed and reviewed together. Every task that commits leaves `./gradlew check` green. Every test's display name
starts with the requirement ID it covers. Tasks tagged `[TDD-EXCEPTION: …]` skip test-first and are verified by the named check; they need the user's
approval ([plan.md](plan.md#tdd-exceptions)).

Already done, and not planned again: the README rewrite (headline, quick start, examples, Guarantees, Limitations) and `docs/examples.md`.

## Format: `[ID] [P?] [Owner?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[USER]**: Owned by the user, the maintainer. **No agent performs these tasks**: they use the maintainer's accounts or credentials, or they
  are steps the agents may not take (merging, changing visibility, publishing). They change no repository file, and are ticked when the user says
  they are done.
- **[Story]**: The user story the task belongs to (US1: a developer can evaluate the library in minutes; US2: the library is published). REL-03
  belongs to no scenario in the spec; its tasks are tagged US1, because the reference serves the same reader.
- **[TDD-EXCEPTION: check]**: config-only task and the check that verifies it

Path abbreviations:

| Short | Path |
|---|---|
| `demo/` | `examples/propagation-demo/` |
| `demomain/` | `examples/propagation-demo/src/main/java/io/github/boundrail/context/demo/` |
| `demotest/` | `examples/propagation-demo/src/test/java/io/github/boundrail/context/demo/` |
| `it/` | `integration-tests/src/test/java/io/github/boundrail/context/it/` |
| `published` | `build-logic/src/main/kotlin/boundrail.published.gradle.kts` |

Verify commands, each followed by `./gradlew check` before the commit:

- **Demo**: `./gradlew :examples:propagation-demo:test --tests '<TestClass>'`
- **IT**:

  ```bash
  ./gradlew --continue \
            :integration-tests:integrationTestJdk21 --tests '<TestClass>' \
            :integration-tests:integrationTestJdk25 --tests '<TestClass>'
  ```

- **Lines**: the check in the `markdown-conventions` skill, on each Markdown file changed

## Phase 1: User Story 1 - The demo (Priority: P1) 🎯 MVP, PR A

**Goal**: One script runs the three demos, in a few lines each, from a clean checkout and Docker.

**Independent Test**: `./examples/propagation-demo/run-demo.sh` from a clean copy of the repository.

- [x] T001 [US1] Demo 1. Red: `demotest/ContextLossDemoTest.java`, `REL-02 demo 1 a plain executor loses the tenant and the test catches
      MISSING_REQUIRED`: a `@BoundrailTest` that runs an internal `orders` call on a plain executor inside a `tenant` scope, and asserts that its
      future fails with `ContextViolationException` and that `ContextAssertions.assertThatViolations()` holds one `MISSING_REQUIRED`; it prints the
      Demo 1 title and at most three lines. It does not compile. Green: `demo/build.gradle.kts` (`application`, Spring Boot BOM, `project(...)`
      dependencies on `-spring` and, for tests, `-test`, the catalog's webmvc, restclient and test starters; `testLogging` that shows the printed
      lines), `demomain/DemoPolicy.java` (`TENANT`, as in the README), `demomain/EdgeService.java` with its internal `orders` client. Verify:
      **Demo**. (REL-02)
- [x] T002 [US1] Demo 2. Red: `demotest/ExternalCallDemoTest.java`, `REL-02 demo 2 the tenant set on the partner call is stripped and reported`,
      using a new `demotest/DemoServices.java` that starts services on random ports, real HTTP throughout (BLD-07): `DemoClient.externalCall()` prints
      a title and at most three lines stating that `partner` received no `Tenant-Id` and that `edge` reported `FORBIDDEN_HEADER`, and a run in which
      `partner` did receive the header fails. Green: `demomain/PartnerService.java` (returns the headers it received), the `edge` endpoint with a
      client declared `BoundrailClients.external()`, a `ViolationListener` bean and the tenant bound by `Context.with`, and
      `demomain/DemoClient.java`. Verify: **Demo**. (REL-02, §15 demo 2)
- [x] T003 [US1] Demo 3, deadline. Red: `demotest/DeadlineDemoTest.java`, `REL-02 demo 3 the deadline shrinks from edge to orders`:
      `DemoClient.deadline()` reports a budget on `edge` greater than the budget on `orders`, which is greater than 0, and never asserts a number.
      Green: `demomain/OrdersService.java` (`inbound.trust=cidr`, `127.0.0.1/32` in the test), the `edge` endpoint and `deadline.default-inbound=2s`,
      `spring.http.clients.imperative.factory=jdk`, the warm-up call in `DemoClient`. Verify: **Demo**. (REL-02, §15 demo 3)
- [x] T004 [US1] Demo 3, Kafka. Red: add to `demotest/DeadlineDemoTest.java`, `REL-02 demo 3 a Kafka event handled after the deadline keeps the tenant
      and carries no deadline`, against a Testcontainers broker (`apache/kafka:4.2.1`, BLD-07): `orders` publishes, the listener in `edge` waits
      until the budget has passed, `DemoClient.deadline()` prints the `event` line with `tenant` and no deadline, and the demo stays within a title
      and three lines. Green: `-kafka` project dependency and `spring-boot-starter-kafka` in `demo/build.gradle.kts`, `testcontainers-kafka` for
      tests, the `KafkaTemplate` send in `OrdersService`, the listener in `EdgeService`, `spring.kafka.*` properties (`auto-offset-reset=earliest`).
      Verify: **Demo**. (REL-02, §15 demo 3)
- [x] T005 [US1] [TDD-EXCEPTION: from a clean copy of the repository with no build cache, `run-demo.sh` exits 0 in under 10 minutes and prints the
      three demos; with a temporary break of `DemoClient`'s Demo 2 check it exits non-zero; no container remains afterwards] Create `demo/Dockerfile`,
      `demo/Dockerfile.dockerignore`, `demo/compose.yaml` and `demo/run-demo.sh` (executable, mode 100755), as in
      [plan.md](plan.md#demo). Add `DemoClient`'s `main`, which waits for `edge`, the `main` methods of
      `EdgeService`, `OrdersService` and `PartnerService`, and the `application` plugin's `mainClass`. Print the blank lines between demos, and set
      `boundrail.context.inbound.trusted-cidrs` for `orders` to the private ranges. The image build runs only `ContextLossDemoTest`, because the
      other demo tests need Docker.
      Name the broker `kafka`, advertise `kafka:9092`, and keep topic auto-creation on or create `orders.events`. `DemoClient.deadline()` declares
      `InterruptedException`. Run several cold starts: if a slow first Kafka send makes the real call time out, warm up until a call succeeds. Report the
      real gap between `edge` and `orders`: the user decides after this task whether `edge` gets labelled simulated work. Report the measured
      time (SC-002). Verify as tagged:

      ```bash
      copy="$(mktemp -d)"                                       # the task is not committed yet, so a clone would miss it
      git ls-files --cached --others --exclude-standard | tar -cf - -T - | tar -xf - -C "$copy"
      cd "$copy/examples/propagation-demo"
      time (docker compose build --no-cache --pull && ./run-demo.sh)
      ```

      (REL-02, SC-002, US1 scenario 1)
- [ ] T006 [US1] [TDD-EXCEPTION: the README sample equals a real run with digits normalised; **Lines**; each Guarantees link resolves and its IDs
      exist in the linked test class] In `README.md`, add a `## Demo` section after the quick start, with the one command, what it needs (Docker
      only), and a text sample pasted from a real run of T005, and add `Demo` to the navigation line. No other README change. Verify the sample with
      `diff <(run-demo.sh | sed 's/[0-9]\+/N/g') <(sample | sed 's/[0-9]\+/N/g')`. (REL-01, D89, US1 scenarios 1 and 2)

**Checkpoint**: PR A is ready for the user. REL-01 and REL-02 are satisfied, apart from "SHALL NOT be published", which T012 verifies.

---

## Phase 2: Going public (user)

**Goal**: The repository is public and the namespace request is running, while the agents continue with Phases 3 and 4.

- [ ] T007 [USER] Review PR A (T001 – T006 and the docs), and merge it to `main`.
- [ ] T008 [USER] [US2] Make the repository public, once the demo is on `main` and CI is green there (D86). First confirm the check in
      [plan.md](plan.md#risks): the history and tracked files hold nothing private, and `.claude/` and `.specify/` are meant to be public. Then:
      `gh repo edit boundrail/boundrail-context --visibility public --accept-visibility-change-consequences`. Check that the README's links and CI
      badge work signed out. (D86)
- [ ] T009 [USER] [US2] Sign in to the Central Portal with GitHub, and request the namespace `io.github.boundrail`. Verify it with the temporary
      public repository named after the verification key, or through Central Support (D84), and delete the temporary repository once the Portal shows
      the namespace as verified. Publishing waits for this. (REL-04, D84)

**Checkpoint**: The request is pending, or verified. Phases 3 and 4 do not wait for it.

---

## Phase 3: User Story 1 - Configuration reference (Priority: P1), PR B

**Goal**: A committed `docs/configuration.md` that can't go stale unnoticed.

**Independent Test**: Change a Default in a spec's Configuration table: `./gradlew check` fails and names the command that fixes it.

- [ ] T010 [P] [US1] Red: `it/ConfigurationReferenceIT.java`, `REL-03 the reference has one section per feature in number order with full property
      names`, on two small spec texts given to `it/docs/ConfigurationReference.java`: the section titles come from the first heading, rows keep their
      order, `violations.mode` becomes `boundrail.context.violations.mode`, the suffix of `` `backend` (JVM system property) `` stays, and text
      outside the table is left out. It does not compile. Green: `ConfigurationReference` with the parsing and rendering of
      [plan.md](plan.md#configuration-reference). Verify: **IT**. (REL-03)
- [ ] T011 [US1] Red: add `REL-03 the committed docs/configuration.md matches the Configuration tables of specs 002 – 007`, which regenerates from
      `specs/` and compares; it fails because the file doesn't exist. Green: `main` in `ConfigurationReference`; in
      `integration-tests/build.gradle.kts`, a `JavaExec` task `generateConfigurationReference`, a system property for the repository root, and
      `specs/` and `docs/configuration.md` as inputs of the test tasks; generate and commit `docs/configuration.md`. Prove the check, with both
      changes reverted afterwards: a changed Default in `specs/005-deadlines/spec.md`, and a changed line in `docs/configuration.md`, each make a
      second `./gradlew check` fail, not report up to date, and the message names the command. Add one line to the README's
      Documentation list. Verify: **IT**, **Lines**. (REL-03)

**Checkpoint**: REL-03 satisfied. PR B is ready for the user.

---

## Phase 4: User Story 2 - The publishing build (Priority: P1), PR C

**Goal**: The four projects produce POMs and signed artifacts that Central will accept, with no credentials in the repository.

**Independent Test**: `./gradlew :integration-tests:integrationTestJdk25 --tests '*BuildSmokeIT'` and T018's rehearsal.

- [ ] T012 [US2] Red: in `it/BuildSmokeIT.java`,
      `REL-04 each published project has a POM under io.github.boundrail at 0.1.0, with sources and Javadoc`, looping over the four POMs and module
      metadata files from a new system property `boundrail.it.published-poms`, set in `integration-tests/build.gradle.kts`: `groupId`, `artifactId`
      (the project name), `version`, and `sourcesElements` and `javadocElements` variants; three files don't exist and the fourth has the wrong group.
      Green: catalog alias and `build-logic/build.gradle.kts` dependency on the plugin, `published` applying `com.vanniktech.maven.publish`, root
      `gradle.properties` (`group`, `version`), the convention in the four projects' build scripts, and `maven-publish`, the `publishing` block and
      its comment removed from `boundrail-context-test/build.gradle.kts` (D85). TEST-01, TEST-04 and BLD-04 stay green. Verify: **IT**;
      `./gradlew help --warning-mode=all` reports nothing new from the plugin, or the task report lists it;
      `./gradlew :integration-tests:tasks --all :examples:propagation-demo:tasks --all` lists no publishing task. (REL-04, REL-02 not published)
- [ ] T013 [US2] Red: `REL-04 every dependency in each published POM has a version`, directly or through an imported BOM; it fails on
      `junit-jupiter-api` and `assertj-core` in `boundrail-context-test`, and project dependencies must be at `0.1.0`. Green:
      `versionMapping { allVariants { fromResolutionResult() } }` in `published` (D85). Verify: **IT**. (REL-04)
- [ ] T014 [US2] Red: `REL-04 each published POM has the metadata Central requires`: the name, description, URL, Apache-2.0 licence
      and SCM that REL-04 lists. Green: the `pom` block in `published`, and a `description` per project. Verify: **IT**. (REL-04, constitution)
- [ ] T015 [US2] Red: `REL-04 the developers section of each published POM names jordi1st with https://github.com/jordi1st`, exactly one developer.
      Green: the `developers` block in `published`, with no `email`. Verify: **IT**. (REL-04, D85)
- [ ] T016 [US2] Guard: `REL-04 no published POM or module metadata contains an email address`, for all four projects: no `email` element, and no text
      matching an address pattern anywhere in the file. Green before the task; show it red against both temporary changes in
      [plan.md](plan.md#guard-tests), then revert them, and report both reds. Verify: **IT**. (REL-04)
- [ ] T017 [US2] [TDD-EXCEPTION: T018; `./gradlew publishToMavenCentral --dry-run` lists sign and publish tasks of exactly the four projects;
      `./gradlew check` is green with no key] In `published`, add `publishToMavenCentral(false, DeploymentValidation.VALIDATED)` and
      `signAllPublications()`. Nothing about keys or tokens enters the repository. Verify also with `--warning-mode=all`. (REL-04, D85)
- [ ] T018 [US2] Rehearsal, no commit, no credentials, and nothing kept but the outcome. With a throwaway key in a temporary `GNUPGHOME`, passed as
      `ORG_GRADLE_PROJECT_signingInMemoryKey` and `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`, run on the wrapper's Gradle 9.8.0
      `./gradlew publishToMavenLocal -Dmaven.repo.local=<temporary directory> --warning-mode=all`. Confirm and report:
      1. all four projects have a JAR, sources JAR, Javadoc JAR, POM and module file, each with an `.asc` that passes `gpg --verify`;
      2. `jar tf` of the core JAR shows `META-INF/versions/25/` and the manifest has `Multi-Release: true`;
      3. the run without a key fails at signing, as the plugin's rule says;
      4. `-Pgroup=io.github.jordi1st` changes the POM's `groupId` (for T020), and the repository is unchanged afterwards (`git status`).

      The planner records the outcome in plan.md. On a failure, stop and follow [plan.md](plan.md#risks). (REL-04, D85 risk)

**Checkpoint**: REL-04's build is finished. PR C is ready for the user; signing is proved on Gradle 9.8.0.

---

## Phase 5: User Story 2 - The release (Priority: P1)

**Goal**: 0.1.0 is on Maven Central.

**Independent Test**: A fresh Gradle project resolves the four artifacts at `0.1.0` from Maven Central.

- [ ] T019 [P] [USER] [US2] Create the release credentials. Needs only T009's Portal account, so it can start at once. Generate a GPG signing key,
      with the name `jordi1st` alone as its user ID and no email, and send the public key to a key server Central reads. Generate a Portal user token.
      Put `mavenCentralUsername`, `mavenCentralPassword`, `signingInMemoryKey`, `signingInMemoryKeyId` and `signingInMemoryKeyPassword` in
      `~/.gradle/gradle.properties`, or in
      `ORG_GRADLE_PROJECT_*` environment variables; never in the repository. Back up the key. (REL-04)
- [ ] T020 [USER] [US2] Needs T018, T019. Upload rehearsal under the personal namespace, from the PR C branch:
      `./gradlew publishToMavenCentral -Pgroup=io.github.jordi1st`. The command should succeed, and the Portal should accept the deployment and hold
      it for review, with all files signed. Then **drop the deployment; do not publish it**. Report the outcome, including what `VALIDATED` did, for
      the planner to record. On a failure, follow [plan.md](plan.md#risks). (REL-04, D85 risk)
- [ ] T021 [US2] Before anything is released: the whole-feature review (`requesting-code-review`, `reviewer` agent) of REL-01 – REL-04, and a fresh
      `./gradlew check`. Read one generated POM per project in full. Fix findings as follow-up tasks (T021a, …), then report to the user that the
      build is ready for release.
- [ ] T022 [USER] Review and merge PR B and PR C to `main`; CI is green on `main`.
- [ ] T023 [USER] [US2] Needs T009 verified, T019, T021, T022. Upload from a clean `main`: `./gradlew publishToMavenCentral`. Never run
      `publishAndReleaseToMavenCentral`. The deployment stops for review in the Portal. (REL-04)
- [ ] T024 [USER] [US2] In the Portal, inspect the deployment: four projects at `0.1.0`, under `io.github.boundrail`, each with a JAR, sources,
      Javadoc, signatures and a POM whose `developers` has no email. Then press Publish. **This cannot be undone**, so drop the deployment instead if
      anything is wrong. (REL-04, SC-001)
- [ ] T024a [USER] [US2] After T024: tag `v0.1.0` on the uploaded commit, push the tag, and create a GitHub Release from it with short notes.
- [ ] T025 [US2] After T024 and Central's sync. No commit. In a new temporary directory, a Gradle project with only `mavenCentral()` depends on the
      four artifacts at `0.1.0`, resolves them and compiles a class using `ContextKey`. Report the output. (REL-04, US2 scenario 1, SC-001)
- [ ] T026 [US2] [TDD-EXCEPTION: `./gradlew check` is green; **Lines**] Close M7, in PR D: set M7's status in `docs/roadmap.md` to Done, tick the
      Exit Checklist below, remove the README's "Pre-release" note and add the Maven Central badge. Last, run `./gradlew check`, which covers JDK 21,
      JDK 25 and
      the forced ThreadLocal run, fresh, and report it.

**Checkpoint**: M7 is closed.

---

## Dependencies & Execution Order

- Demo: T001 → T002 → T003 → T004 → T005 → T006 → T007.
- Going public: T007 → T008 → T009 (D86). T019 needs T009's Portal account, not the verified namespace.
- Configuration reference: T010 → T011.
- Publishing build: T012 → T013 → T014 → T015 → T016 → T017 → T018.
- Rehearsal upload: T020 needs T018 and T019.
- Release: T021 needs T011 and T018, and T020 if it runs. Then T021 → T022. T023 needs T009 verified, T019 and T022. Then T023 → T024 → T025 → T026.
- T001 – T004 touch `EdgeService`, `DemoClient` and `demo/build.gradle.kts`, so they run in sequence.
- T010 has no dependency on the demo or the publishing build. T011 and T012 both change `integration-tests/build.gradle.kts`, so they run in sequence.
- T012 – T017 all change `published` or `BuildSmokeIT`, so they run in sequence.
- T016 comes after T015, so its temporary change has a `developer` block to add to.
- PR A comes first in practice, because T008 and T009 wait for it (D86); the agents continue with Phases 3 and 4 during the namespace wait.
- Every task that commits is reviewed and committed on its own, on branch `008-release`; pushing and merging need the user.

## Exit Checklist

- [x] The user approved the plan, tasks, TDD exceptions (T003's `factory` line, T005, T006, T017, T026) and guard test (T016)
- [x] Q1 settled and written into spec.md; Q2 – Q7 answered
- [ ] T018's outcome recorded in plan.md, and T020's if it ran
- [ ] Requirements done: REL-01 – REL-04
- [ ] T016's guard shown red against both temporary changes, and the changes reverted
- [ ] T011's stale check shown failing for a changed spec and a changed reference, and the changes reverted
- [ ] `run-demo.sh` ran from a clean copy of the repository in under 10 minutes (SC-002)
- [ ] 0.1.0 on Maven Central, and resolved by a fresh project (T025)
- [ ] `./gradlew check` green
