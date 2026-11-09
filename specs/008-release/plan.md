# Implementation Plan: v0.1 Release

**Branch**: `008-release` | **Date**: 2026-11-06 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/008-release/spec.md`, decisions D84 – D89 in [design.md](../../docs/design.md), roadmap
[M7](../../docs/roadmap.md#m7--v01-release), the code at `main` 3ac09e1 with the uncommitted spec, plan, design and roadmap edits, and the
public API of `gradle-maven-publish-plugin` 0.37.0 from the Gradle cache (read, not run)

**Status**: Approved by the user on 2026-11-06, with [tasks.md](tasks.md), the [TDD exceptions](#tdd-exceptions), the
[guard test](#guard-tests) and the answers to [Q1 – Q7](#spec-gaps-and-open-questions).

## Summary

A public release good enough to put in front of experienced Spring developers. M7 closes when 0.1.0 is on Maven Central.

The README rewrite (headline, quick start, examples, Guarantees, Limitations) and `docs/examples.md` are done and are not planned again. What
remains:

- `examples/propagation-demo`: two small Spring Boot services (`edge`, `orders`), a fake external service (`partner`) and Kafka in Docker Compose,
  with one script that runs the three demos of design.md §15 in a compact output (REL-02, D87). It uses in-repo project dependencies and is never
  published.
- The README's demo instructions and a text sample of the demo's output (REL-01, D89).
- A generated, committed `docs/configuration.md`, and a test in `./gradlew check` that fails when it is stale (REL-03, D88).
- A `boundrail.published` convention plugin around `com.vanniktech.maven.publish` 0.37.0, applied to the four published projects, with tests of
  their POMs, including the no-email rule of REL-04 (D84, D85).
- A signing rehearsal with a throwaway key, because signing and the upload were not exercised in the D85 spike and Gradle 9.8.0 is above the
  plugin's stated compatibility.
- The maintainer's steps, as tasks owned by the user: making the repository public (D86), the Central namespace request and verification (D84),
  the GPG key and Central credentials, the upload, and the final publish from the Portal (D85).

## Technical Context

**Language/Version**: Java 21 (`--release 21`) for the demo, and for test code in `integration-tests`; Kotlin DSL for the build; Gradle 9.8.0

**Primary Dependencies**:

- `com.vanniktech.maven.publish` 0.37.0 (D85), a new `build-logic` dependency through a catalog alias for
  `com.vanniktech:gradle-maven-publish-plugin:0.37.0`.
- The demo: the catalog's existing `spring-boot-starter-webmvc`, `spring-boot-starter-restclient`, `spring-boot-starter-kafka`,
  `spring-boot-starter-test` and `testcontainers-kafka` aliases, and `project(...)` dependencies on `-spring`, `-kafka` and `-test`. Gradle's own
  `application` plugin gives `installDist`, so the demo needs no new plugin.
- Docker and Docker Compose for the demo; image `apache/kafka:4.2.1`, as in the topology, and `eclipse-temurin:25-jdk`.

**Storage**: N/A

**Testing**:

- `examples/propagation-demo` tests run in `./gradlew check`: real services on random ports and, for Kafka, a Testcontainers broker (BLD-07).
- `integration-tests`: `BuildSmokeIT` gains the REL-04 POM tests for all four projects; `ConfigurationReferenceIT` holds the REL-03 tests.
- `run-demo.sh` is verified by hand, not in `./gradlew check` (TDD exception, T005).
- Signing is verified by the rehearsal with a throwaway key (T018); the upload only by the maintainer (T020, T023).

**Target Platform**: the host needs only Docker for the demo; a JDK 25 toolchain and JDK 21 for the build, as today

**Project Type**: library release

**Performance Goals**: SC-002: under 10 minutes from a clean checkout to the demo's output, with a cold Docker cache (measured in T005)

**Constraints**:

- BLD-02 lists six projects, so the demo stays one Gradle project: its services are main classes in that project, not subprojects.
- `integration-tests` and `examples/propagation-demo` never apply the publishing convention (REL-02, BLD-02).
- No secret ever enters the repository: credentials and keys live in the maintainer's `~/.gradle/gradle.properties` or environment.
- A released POM cannot be removed from Central (D85), so nothing is uploaded until T021's review has passed.

**Scale/Scope**: 4 requirements (REL-01 – REL-04); 26 tasks: 18 for the agents (14 red-green or exception tasks, the signing rehearsal, the
feature review, the consumer check and the close) and 8 owned by the user ([tasks.md](tasks.md))

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|---|---|---|
| I – VI Library invariants | Pass | No library code changes; the demo uses the public API only. It binds the tenant with `Context.with` (scope-only) |
| VII Spec-first | Gate | README guarantees link to existing acceptance tests; no new behaviour. Q1 is written into REL-04 |
| VII Test-first | Pass | Every code task is one red-green cycle; the exceptions are listed in [TDD Exceptions](#tdd-exceptions) for the user's approval |
| VIII Simplicity | Pass | No new Gradle project, root build script or Gradle plugin for the demo; the generator is one test-side class; no publishing from CI |
| Technical constraints | Gate | Apache-2.0 licence and POM metadata on every published artifact; no email address in a POM (REL-04, T014 – T016) |

## Scope and Approach (from ROADMAP M7)

- A README with the headline, a quick start, usage examples from simple to advanced, a "Guarantees" section that links to the acceptance tests,
  the instructions to run the demo, and a sample of the output of the three demos (D89).
- `examples/propagation-demo`: two Spring Boot services, a fake external service and Kafka in Docker Compose, with one script (D87).
- A configuration reference, `docs/configuration.md`, generated from the Configuration tables in the feature specs (D88).
- Make the repository public once the demo is on `main`, before the Central namespace request (D86).
- Publish 0.1.0 to Maven Central under `io.github.boundrail` (D84), with the tooling D85 names.

## Pull Requests

The namespace verification is the only step whose duration nobody here controls, and it can start only after the repository is public, which needs
the demo on `main` (D86). So the demo comes first, and the rest of the work runs while Central verifies the namespace.

| PR | Tasks | Merged by the user | Unblocks |
|---|---|---|---|
| A: demo and README | T001 – T006, with the uncommitted spec, plan, tasks, design and roadmap edits | T007 | Going public, the namespace request |
| B: configuration reference | T010 – T011 | T022 | REL-03 |
| C: publishing build | T012 – T017 | T022 | The rehearsal, the upload |
| D: close | T026 | After T026 | M7 closed |

All four use branch `008-release`, as PR #1 did, one at a time, each starting from the merged `main`. PR B and PR C need no credentials, and can be
reviewed while the namespace request is pending. The upload runs from `main` after PR C is merged.

## Research Notes

### Demo

**Shape.** One Gradle project, main classes `EdgeService`, `OrdersService` and `PartnerService`, each a `@SpringBootConfiguration` with
`@EnableAutoConfiguration` and no component scan, as the topology's `EdgeApp` is, so the services in one package do not pick each other up.
`DemoClient` is a plain main class: it calls `edge`, prints the lines, and fails if a service did not observe what the demo claims. Its methods are
what the tests call, so the output the script prints is the output the tests pin down.

**Flows.**

- Demo 1 is a real test, as §15 says, and the same code as the README's `ContextLossTest`: `ContextLossDemoTest` uses `@BoundrailTest`, submits work
  to a plain executor inside a `tenant` scope, and asserts that the work's future fails with `ContextViolationException`, kind `MISSING_REQUIRED`.
  It asserts the failure, so it passes, and `./gradlew check` stays green.
- Demo 2: `edge` sets `Tenant-Id` on a call declared `BoundrailClients.external()`. `partner` returns the headers it received, and `edge` returns
  them with the `FORBIDDEN_HEADER` violation it recorded through a `ViolationListener` bean (WIRE-07, IT-2.4).
- Demo 3: `edge` has `deadline.default-inbound=2s`, does 100 ms of labelled simulated work (`demo.simulated-work`), because a real hop costs about
  1 ms, and calls `orders` through `BoundrailClients.internal("orders")`; both return the budget left on arrival. `orders` publishes an event through
  its `KafkaTemplate`; a listener in `edge` waits until the budget has passed (`demo.event-wait`), and
  records that it has `tenant` and no deadline (DL-05, KAF-01, KAF-02, IT-3.1, IT-3.8). The record's value is an event id that `orders` returns, so
  a run is judged on its own event, and `DemoClient` polls `GET /events/{event}` on `edge`, because the request must end before the listener's result
  exists.

**Tenant at the edge.** `edge` binds the tenant in its handler with `Context.with(TENANT, "acme")`, and keeps the default `inbound.trust=none`. A JWT
would add a key pair and a token issuer to a demo about something else, and trusting the demo client's network at the edge would contradict the
README's warning. A comment in `EdgeService` points to the quick start's token step. Only `orders` sets `inbound.trust=cidr`: the private ranges
in Docker, because a fixed subnet could collide with a developer's network and `orders` can't be reached from outside Compose, and `127.0.0.1/32` in
the tests.

**Clients.** `spring.http.clients.imperative.factory=jdk`, because the deadline's per-request timeout needs the JDK request factory (D51).

**Docker.** Acceptance scenario 1 gives the developer a clean checkout and Docker, so the host needs no JDK. A single-stage image from
`eclipse-temurin:25-jdk` builds the demo with the wrapper, and serves every container:

- `Dockerfile` copies the repository and runs `installDist`, then the Demo 1 test once, so that Gradle and the dependencies are in the image.
- `compose.yaml` runs `kafka`, `edge`, `orders` and `partner`, each service with `java` and its main class, on the image's `CLASSPATH`. `client` and
  `context-loss-test` sit under a `demo` profile, so `up` does not start them. Nothing is published to a host port, so the demo can't collide with
  a local service.
- `Dockerfile.dockerignore` sits beside the Dockerfile and keeps `.git`, `build`, `.gradle` and `.idea` out of the context, and nothing is added to
  the repository root.
- `run-demo.sh` runs `docker compose up --build --detach`, then the test (`./gradlew --offline --rerun ... --tests '*ContextLossDemoTest'`), then
  `client`, and always ends with `docker compose down`. Gradle's output is filtered to the lines Demo 1 prints. With `set -euo pipefail`, a failed
  demo fails the script. It uses nothing newer than bash 3.2, for macOS.
- `DemoClient` retries until `edge` answers, so the services need no health checks; `kafka` has one, for `depends_on`.

**Warm-up.** A cold JVM can eat a 2-second budget, as `DeadlinePropagationIT`'s warm-up shows, so `DemoClient` calls each flow until one call
succeeds, for at most 30 seconds, and discards the results before it prints.

**Output.** Exact text is the implementer's; the shape is a title and at most three lines per demo (REL-02, D89). The tests assert the shape and the
facts. Numbers vary per run:

```text
Demo 1: context loss is caught by a test
  test      a plain executor lost the tenant
  caught    MISSING_REQUIRED, key tenant, boundary INTERNAL_CALL

Demo 2: a tenant is stripped from an external call
  edge      set Tenant-Id: acme on the call to partner
  partner   received no Tenant-Id
  edge      reported FORBIDDEN_HEADER

Demo 3: a deadline shrinks across hops, and a Kafka event is unaffected
  edge      1999 ms left, then 100 ms of simulated work
  orders    1898 ms left
  event     handled after the deadline passed, tenant acme, no deadline
```

### Configuration reference

**Where it lives.** There is no root build script, so a root `check` task doesn't exist. `integration-tests` already runs in `./gradlew check`, with
JUnit and AssertJ, so the generator is a small class in its test sources, `ConfigurationReference`, with a `main`, and a `JavaExec` task
`generateConfigurationReference` in `integration-tests/build.gradle.kts`. A `build-logic` task would need a root project wiring and a test set of its
own, because `build-logic` has none.

**Input.** The six specs each have one `### Configuration` section with one `| Property | Default | Meaning |` table, under
`Properties use the prefix boundrail.context.`, and no row contains a link or an escaped pipe (checked 2026-11-06).

**Output.** A generated-file notice with the regeneration command, then one section per feature, in number order, titled from the spec's first
heading without `Feature Specification: `, with the rows copied in spec order and the property shown with its full `boundrail.context.` name. A
suffix in the cell, such as `(JVM system property)`, stays. Only the tables are copied: the prose about startup failures stays in the specs, and
REL-03 asks for the tables.

**Staleness.** `ConfigurationReferenceIT` regenerates the text and compares it with the committed file; its failure message names the command. The
test reads the repository, so `specs/` and `docs/configuration.md` have to be declared as inputs of the test tasks, as `testModulePom` is. Without
that, Gradle would report the task up to date and a stale file would pass, so T011 proves this with temporary changes.

### Publishing

**Convention plugin.** `boundrail.published` applies `com.vanniktech.maven.publish`, sets the POM, and sets
`versionMapping { allVariants { fromResolutionResult() } }` on the publications (D85). It is applied only in the four published projects.

**Coordinates.** A root `gradle.properties` with `group=io.github.boundrail` and `version=0.1.0`. Today neither is set anywhere, and the published
`artifactId` is the project name. Nothing else reads them: JAR names change, and `BuildSmokeIT` only checks the `.jar` suffix.

**`boundrail-context-test`.** Its `maven-publish` plugin, `publishing` block and comment go, because the plugin creates the `maven` publication
(D85). The POM path `BuildSmokeIT` uses does not change.

**POM tests.** Each published project's `generatePomFileForMavenPublication` and `generateMetadataFileForMavenPublication` feed the integration test
tasks, as `boundrail-context-test`'s already do. The new tests loop over the four POMs, so a rule is checked for every project and not only for the
one `BuildSmokeIT` reads today. That needs one new system property, `boundrail.it.published-poms`, listing the POM paths.

**Manual release.** The 0.37.0 API has `publishToMavenCentral(boolean, DeploymentValidation)` with `NONE`, `VALIDATED` and `PUBLISHED`. The plan uses
`publishToMavenCentral(false, DeploymentValidation.VALIDATED)`: no automatic release, and the build waits for the Portal's own checks, so a rejected
deployment fails the upload command instead of going unseen. The exact meaning of `VALIDATED` was read from the API, not observed, so T020 or T023
confirms it. The upload command is `./gradlew publishToMavenCentral`, never `publishAndReleaseToMavenCentral`, which would release without the
maintainer's review.

**Signing.** `signAllPublications()`. The key comes from the Gradle properties `signingInMemoryKey`, `signingInMemoryKeyId` and
`signingInMemoryKeyPassword`. The plugin's bytecode makes signing required unless the version ends in `-SNAPSHOT`, so at `0.1.0`,
`publishToMavenLocal` without a key should fail (T018 confirms it). `./gradlew check` doesn't sign, so CI needs no key, and T017 proves that.

**Rehearsal (T018).** No credentials are needed to exercise signing: a throwaway key in a temporary `GNUPGHOME` and a temporary Maven repository
(`-Dmaven.repo.local`) sign all four projects on Gradle 9.8.0. Nothing from it is kept.

**Upload rehearsal (T020).** D84 says Central registers `io.github.<username>` automatically, so the maintainer owns
`io.github.jordi1st` as soon as they sign in. An upload with `-Pgroup=io.github.jordi1st` exercises the real upload and the Portal's checks while
`io.github.boundrail` is still being verified, with no change to the repository. The deployment is then dropped, never published. T018 confirms that
`-Pgroup` reaches the POM.

## Spec Gaps and Open Questions

The user accepted every answer below on 2026-11-06, with Q7 extended to a GitHub Release. Q1 is written into REL-04.

| Gap | Status | Question | Recommendation | Tasks |
|---|---|---|---|---|
| Q1 | Settled 2026-11-06 | REL-04 specifies only `developers`, but the constitution asks for POM metadata on every artifact, and Central requires name, description, URL, licence and SCM. What are the values? | `name` is the project name; `description` is the project's Contents cell in design.md §12; `url` and `scm` point to `https://github.com/boundrail/boundrail-context`; the licence is `Apache-2.0` with its URL. Add them to REL-04 | T014 |
| Q2 | Settled 2026-11-06 | `docs/configuration.md` has no way in from the README, but the brief limits the remaining README work to the demo. May T011 add one line to the README's Documentation list? | Yes: REL-03 calls it user-facing, and a reference nobody can find doesn't serve that | T011 |
| Q3 | Settled 2026-11-06 | The README's "Pre-release" note says nothing is published, which is false once T024 is done. May T026 remove it? | Yes: after T025 confirms 0.1.0 resolves, in PR D | T026 |
| Q4 | Settled 2026-11-06 | Rehearse the upload under `io.github.jordi1st` and drop the deployment? It is optional, and needs T019's key and token | Yes: it is the only way to exercise the upload on Gradle 9.8.0 before an irreversible release, and it fits in the namespace wait | T020 |
| Q5 | Settled 2026-11-06 | Split into the four pull requests of [Pull Requests](#pull-requests)? | Yes: the demo first, so the namespace wait overlaps the rest | All |
| Q6 | Settled 2026-11-06 | Should the GPG key's user ID hold an email address? Central needs the public key on a public key server, where it cannot be fully withdrawn | No: use the name `jordi1st` alone, the same reasoning as D85. Check that `keyserver.ubuntu.com` serves it, because `keys.openpgp.org` hides user IDs without a verified email | T019 |
| Q7 | Settled 2026-11-06 | Tag the released commit? Neither the spec nor the brief asks for it, so no task does | Yes, by the user after T024: tag `v0.1.0` on the uploaded commit and create a GitHub Release, as 10 of the 12 compared projects do | T024a |

## Guard Tests

These tests already pass before the task that introduces them, so their red is shown against a named temporary change, which is reverted before the
commit. They are not TDD exceptions. They need the user's approval with the plan.

| Task | Test | Why it is green first | Temporary change that shows it red |
|---|---|---|---|
| T004 | `REL-02 demo 3 a consumer group that joins after the event was published still handles it` (approved 2026-11-06) | `auto-offset-reset=earliest` is already set | Remove that line from `edge.properties` |
| T004 | `REL-02 demo 3 a run is judged on its own event, not the warm-up's` (approved 2026-11-06) | The `/events` handler already looks up the event id | Return the first handled event instead |
| T016 | `REL-04 no published POM or module metadata contains an email address` | No POM contains an address, with or without `developers`, and adding one would be the bug | `email = "jordi1st@example.com"` on the `developer` block of `boundrail.published`; a second run with the address in the POM's `description`, which an `<email>` check alone misses |

The "no email" assertion has its own test, and is not a negative assertion beside T015's positive ones, because a released POM cannot be removed:
this rule should fail by name.

## TDD Exceptions

For the user's approval. Each names the check that verifies it.

| Task | What | Why there is no failing test first | Verified by |
|---|---|---|---|
| T003 | `spring.http.clients.imperative.factory=jdk` in `edge.properties` (added 2026-11-06) | Boot already picks the JDK client on this classpath, so removing the line changes nothing; capping itself is proved by IT-3.2 | No `not the JDK HttpClient` WARN in the demo's test output |
| T005 | `Dockerfile`, `Dockerfile.dockerignore`, `compose.yaml`, `run-demo.sh`, the `main` methods of `EdgeService`, `OrdersService`, `PartnerService` and `DemoClient`, and the blank lines between demos (widened 2026-11-06) | Config and a shell script; the behaviour inside them is pinned by T001 – T004, and running Compose inside `./gradlew check` would make every build slow and depend on Compose | `./examples/propagation-demo/run-demo.sh` exits 0 and prints the three demos, from a clean copy of the repository with no build cache, in under 10 minutes; with a temporary break of `DemoClient`'s Demo 2 check, it exits non-zero; `docker compose ps -a` is empty afterwards |
| T006 | README demo section and output sample | Documentation | The sample equals a real run's output with digits normalised (command in the task); the markdown line check passes; each Guarantees link resolves and the IDs it cites exist in its test class |
| T017 | Signing and manual release in `boundrail.published` | Exercising them in `check` would need a key in the build and a nested Gradle run | T018, and `./gradlew publishToMavenCentral --dry-run` lists sign and publish tasks of exactly the four projects; `./gradlew check` stays green with no key |
| T026 | The roadmap's M7 status row, the README's Pre-release note and the Maven Central badge | Documentation | `./gradlew check` is green, and the markdown line check passes |

Not exceptions: `gradle.properties`, the catalog alias and the `build-logic` changes are the green steps of T012's failing tests. T018 is a rehearsal,
and nothing from it is kept but its outcome in this plan. User-owned tasks change no repository file.

## Project Structure

### Documentation (this feature)

```text
specs/008-release/{spec.md, plan.md, tasks.md}
```

### Source Code (repository root)

```text
README.md                                   # demo section, output sample (T006)
docs/configuration.md                       # generated (T011)
gradle.properties                           # new: group and version (T012)
gradle/libs.versions.toml                   # plugin alias (T012)
build-logic/build.gradle.kts                # plugin dependency (T012)
build-logic/src/main/kotlin/
└── boundrail.published.gradle.kts          # new: publishing convention (T012 – T017)
boundrail-context-{core,spring,kafka,test}/build.gradle.kts   # apply the convention (T012)
integration-tests/build.gradle.kts          # POM inputs (T012), reference task and inputs (T011)
integration-tests/src/test/java/io/github/boundrail/context/it/
├── BuildSmokeIT.java                       # REL-04 tests (T012 – T016)
├── ConfigurationReferenceIT.java           # new (T010, T011)
└── docs/ConfigurationReference.java        # new: the generator (T010, T011)
examples/propagation-demo/
├── build.gradle.kts
├── Dockerfile                              # new (T005)
├── Dockerfile.dockerignore                 # new (T005)
├── compose.yaml                            # new (T005)
├── run-demo.sh                             # new, executable (T005)
└── src/
    ├── main/java/io/github/boundrail/context/demo/
    │   ├── DemoPolicy.java
    │   ├── EdgeService.java
    │   ├── OrdersService.java
    │   ├── PartnerService.java
    │   └── DemoClient.java
    └── test/java/io/github/boundrail/context/demo/
        ├── ContextLossDemoTest.java
        ├── DemoServices.java               # starts the services on random ports
        ├── ExternalCallDemoTest.java
        └── DeadlineDemoTest.java
```

**Structure Decision**: no new Gradle project and no root build script. The demo stays the one project BLD-02 lists, and the generator lives in the
test sources of a project `check` already runs.

## Requirement Coverage

| Requirement or scenario | Tasks |
|---|---|
| REL-01 | Done: headline, quick start, examples, Guarantees. T006: demo instructions, output sample, link check |
| REL-02 | T001 – T004 (the three demos, compact output, project dependencies), T005 (Docker Compose, one script), T012 (not published) |
| REL-03 | T010 (generator), T011 (committed file, stale check) |
| REL-04 | T012 – T017 (build, POM rules), T018 (signing), T009, T019, T020, T023, T024 (maintainer steps), T025 (resolves from Central) |
| US1 scenario 1 | T005, T006 |
| US1 scenario 2 | T006 |
| US2 scenario 1 | T025 |
| SC-001 | T024, T025 |
| SC-002 | T005 (timed from a clean copy of the repository) |

## Risks

The group and package are settled (DESIGN D84), but Central must verify the `io.github.boundrail` namespace by hand, and publishing waits for it.
Signing and the upload were not exercised in the spike, and Gradle 9.8.0 is above the plugin's stated compatibility (DESIGN D85).

- **Signing on Gradle 9.8.0.** T018 exercises it with no credentials, early, before the namespace is verified, and checks every signature with `gpg`.
  T012 and T017 also run with `--warning-mode=all`, and report any deprecation that comes from the plugin.
- **Upload on Gradle 9.8.0.** T018 can't reach the Portal. T020 can, and a failure there costs nothing: the deployment is dropped.
- **If either fails on 9.8.0.** Debug it to the root cause first (`systematic-debugging`). Then, in this order, for the user to decide:
  1. For an upload failure only: publish to a temporary local file repository, which writes checksums, zip it, and upload the bundle in the Portal's
     web interface. The signed files are the same, and no build file changes.
  2. Run the plugin on Gradle 9.6.0, its last stated version, with a wrapper change in a reviewed task, after `./gradlew check` passes on it.
  3. Change the tooling, which reopens D85 and goes through the spec-creator.
- **A release is permanent.** T021 reviews everything first, T023 stops at the Portal's review state, and T024 is the maintainer's own action, after
  they have read the POMs in the Portal. `publishAndReleaseToMavenCentral` must not be used.
- **Namespace wait.** Its length is outside the project's control. Starting it after PR A lets PR B, PR C and T018 – T021 run during it. If the
  temporary-repository check stalls, D84 names Central Support.
- **A secret in the repository.** Keys and tokens stay in `~/.gradle/gradle.properties` or the environment, so no task writes them to a file in the
  repository, and T018's throwaway key lives in a temporary directory.
- **Going public.** Visibility can be reverted, but copies can't. A read-only check on 2026-11-06 found: all commits are authored with the GitHub
  noreply address; tracked files contain no `/home/jordi` path or personal address; the secret-like strings found outside `specs/` and `docs/` are
  test fixtures; `.claude/settings.local.json` is not tracked. It was a `git grep`, not a secret scanner, so T008 asks the maintainer to confirm,
  including that `.claude/` and `.specify/` should be public.
- **Demo timing.** The first Gradle build runs inside `docker build`, with a cold cache, and has to fit SC-002. T005 measures it. If it takes more
  than 10 minutes, that goes to the user before any change.
- **Flaky budget.** The `orders` budget depends on real time, so tests assert `edge > orders > 0`, never a number, and `DemoClient` warms up first.
- **Docker in `check`.** The demo's tests add a Testcontainers broker to `./gradlew check`, which already needs Docker.
- **Stale cached check.** Covered by the declared inputs and the temporary changes in T011.

## Complexity Tracking

None.
