# Feature Specification: Foundation

**Feature Branch**: `001-foundation`

**Created**: 2026-09-28

**Status**: Implemented

**Milestone**: M0

**Input**: Roadmap milestone [M0](../../docs/roadmap.md#m0--foundation).

## User Scenarios & Testing *(mandatory)*

The users of this feature are the library's developers, human or agent. It delivers a repository where the specification is executable before any
feature exists.

### User Story 1 - Multi-release build (Priority: P1)

A developer builds the project with one command and gets a core JAR that carries a Java 21 variant and a Java 25 variant of its classes.

**Why this priority**: Every later feature depends on the multi-release JAR and the toolchain plumbing. They are fiddly, so they are proven first.

**Independent Test**: Build the core JAR and run the build smoke test on JDK 21 and on JDK 25.

**Acceptance Scenarios**:

1. **Build smoke test** (BLD-11): **Given** the packaged core JAR, **When** the smoke test runs on JDK 25, **Then** the marker class reports version
   25; **When** it runs on JDK 21, **Then** it reports the base version. *(BLD-04, BLD-06, BLD-11)*

---

### User Story 2 - Integration tests against packaged JARs on both JDKs (Priority: P1)

A developer runs `./gradlew check` and the integration tests run three times: on JDK 21, on JDK 25, and on JDK 25 with the ThreadLocal backend
forced, always against the packaged JARs.

**Why this priority**: Multi-release classes only load from a JAR. Without this, backend-sensitive tests silently test the wrong backend.

**Independent Test**: `./gradlew check` runs `integrationTestJdk21`, `integrationTestJdk25` and `integrationTestJdk25ThreadLocal`, and the smoke test
passes in the first two.

**Acceptance Scenarios**:

1. **Given** the build, **When** `./gradlew check` runs, **Then** all three integration test tasks run, each with its own JDK launcher, and the test
   runtime classpath contains the library JARs, not class directories. *(BLD-06)*

---

### User Story 3 - Executable specification skeleton (Priority: P2)

Every acceptance suite and scenario of features 002–007 exists, compiles, and is disabled with the milestone that enables it. The test topology
(five services, hosts file, Kafka, JWT signing) starts.

**Why this priority**: Later features start by enabling scenarios, not writing them. Writing them first keeps the specification executable.

**Independent Test**: The test report lists every scenario ID from `specs/002`–`007` as skipped; each scenario's `@Disabled("Pending: Mx")`
annotation in the source carries the reason.

**Acceptance Scenarios**:

1. **Given** the integration tests, **When** they run, **Then** RuntimeConformanceIT and the four acceptance suites report every scenario as
   skipped, each scenario's source carries `@Disabled("Pending: Mx")`, and none fails. *(BLD-07, BLD-10)*

---

### User Story 4 - Continuous integration and licence (Priority: P3)

Every push to `main` and every pull request runs `./gradlew check` on GitHub Actions with JDK 21 and JDK 25 installed. The repository is Apache-2.0 licensed.

**Why this priority**: Needed before contributions, but the build works locally without it.

**Independent Test**: Open a pull request and see the `check` job run green.

**Acceptance Scenarios**:

1. **Given** a push to `main` or a pull request, **When** CI runs, **Then** `./gradlew check` runs with JDK 21 and JDK 25 available and passes. *(BLD-08)*

---

### Edge Cases

- Only one JDK is installed locally: the build fails because a required toolchain is missing (JDK 25 for compilation, BLD-04; JDK 21 for
  `integrationTestJdk21`, BLD-06). No toolchain resolver is configured, so Gradle does not download the missing JDK; it reports the toolchain it could
  not find.
- A test accidentally runs against class directories: the smoke test (BLD-11) fails on JDK 25, because the versioned class is not loaded.

## Requirements *(mandatory)*

### Functional Requirements

**BLD-01** `[M0]` The project SHALL be built with Gradle using the Kotlin DSL, a version catalog at `gradle/libs.versions.toml`, and shared
conventions in an included build named `build-logic`.

**BLD-02** `[M0]` The repository SHALL contain these projects:

| Project | Published |
|---|---|
| `boundrail-context-core` | yes |
| `boundrail-context-spring` | yes |
| `boundrail-context-kafka` | yes |
| `boundrail-context-test` | yes |
| `integration-tests` | no |
| `examples/propagation-demo` | no |

**BLD-03** `[M0]` `boundrail-context-core` SHALL have no runtime dependencies outside the JDK, and SHALL log through `java.lang.System.Logger`.

**BLD-04** `[M0]` `boundrail-context-core` SHALL compile its `main` source set with `--release 21`, and a second source set `src/main/java25` with
`--release 25`. It SHALL package the `java25` output under `META-INF/versions/25/` in a JAR whose manifest declares `Multi-Release: true`. The build
SHALL use Gradle toolchains and SHALL require a JDK 25 toolchain.

**BLD-05** `[M0]` Among the published projects, Spring dependencies SHALL be confined to `-spring`, `-kafka` and `-test`, and Kafka dependencies to
`-kafka`. Spring Security and Micrometer SHALL be optional (`compileOnly` plus conditional auto-configuration) in `-spring`.

**BLD-06** `[M0]` `integration-tests` SHALL put the packaged JARs of the library projects on its test runtime classpath, not their class directories.
It SHALL define three test tasks:

- `integrationTestJdk21`, run with a JDK 21 launcher;
- `integrationTestJdk25`, run with a JDK 25 launcher;
- `integrationTestJdk25ThreadLocal`, run with a JDK 25 launcher and the system property `boundrail.context.backend=thread-local`, restricted to
  RuntimeConformanceIT.

`check` SHALL depend on all three. (Rationale: multi-release classes only load from a JAR.) All three SHALL run with the system property
`jdk.net.hosts.file` pointing to the test hosts file ([Test Topology](#test-topology)). Each of them SHALL pass the system property
`boundrail.it.task=<task name>`, for example `boundrail.it.task=integrationTestJdk21`.

**BLD-07** `[M0]` Integration tests SHALL use Testcontainers for Kafka, and SHALL NOT mock Kafka clients or HTTP clients.

**BLD-08** `[M0]` CI (GitHub Actions) SHALL run `./gradlew check` on every push to `main` and every pull request, with JDK 21 and JDK 25 installed.

**BLD-09** `[M0]` The repository SHALL be licensed under Apache-2.0, with a `LICENSE` file at the root.

**BLD-10** `[M0]` Scenarios for later milestones SHALL exist from M0, compile, and be disabled with `@Disabled("Pending: Mx")`. A milestone is
complete only when all of its scenarios are enabled and passing.

**BLD-11** `[M0]` A build smoke test in `integration-tests` SHALL assert that a marker class present in both the base and the `versions/25` variant
reports version 25 when run on JDK 25, and the base version when run on JDK 21.

**BLD-12** `[M0]` The minimum supported runtime SHALL be Java 21. The Spring modules SHALL target Spring Boot 4.x.

### Key Entities

- **Library projects**: the four published projects of BLD-02, each a Gradle project with its own JAR.
- **Marker class**: an internal core class with a base and a `versions/25` variant, used only by the smoke test.
- **Integration test tasks**: the three JDK-specific test tasks of BLD-06.
- **Test topology**: the five test services, hosts file, Kafka container and JWT signing key described in
  [Test Topology](#test-topology).

## Test Topology

This section is shared: the acceptance suites of every feature run on it. 001-foundation builds its skeleton: the `Topology` JUnit extension,
which starts the Testcontainers Kafka broker and the five services as minimal applications on random ports, the test hosts file, the
`ObservedContext` probe, and `TestJwts`. Each later feature adds the parts that depend on its library API, such as the application policy,
destination settings, inbound trust, the edge tenant contributor, the Kafka consumer and the slow endpoint, when it enables the scenarios that
need them.

The acceptance suites in `integration-tests` share the following topology. Each service is a separate Spring Boot application context started in the
same JVM on a random port.

| Service | Role |
|---|---|
| `edge` (A) | Public entry point; OAuth2 resource server that validates test JWTs; tenant contributed from the `tenant` claim |
| `orders` (B) | Internal service |
| `billing` (C) | Internal service; consumes Kafka topic `orders.events` |
| `partner` | External stub that records every header it receives; exposes a slow endpoint |
| `token-exchange` | Stub RFC 8693 endpoint that issues audience-scoped tokens and counts calls |
| Kafka | Testcontainers Kafka broker |

Every service reports the snapshot it observed for each request or record to a shared `ObservedContext` probe, which the tests use for assertions.

Services listen on the loopback interface. The test hosts file (BLD-06) maps `edge.svc.test`, `orders.svc.test`, `billing.svc.test`,
`token-exchange.svc.test`, `partner.example.test` and `localhost` to `127.0.0.1`. It includes `localhost` because, with `jdk.net.hosts.file` set, the
JDK resolves names only from that file. Clients address services by these names.

Every service is configured with `destinations.internal-hosts=[*.svc.test]` and no external hosts. Clients normally also declare their destination,
with `BoundrailClients.internal("orders")` or `BoundrailClients.external()`, because only a declaration supplies a service name.
`partner.example.test` matches no pattern, so an undeclared partner client is `UNKNOWN`.

The shared services start once per test run. A scenario that needs edge with other configuration, or that asserts edge's startup log
entries, such as IT-1.3 – IT-1.5, starts an extra edge instance with the common properties plus its own through
`Topology.Running.startEdge(String... properties)`, and closes it when the test ends.

Because the services share one JVM, JVM-wide state (RT-09) is shared too: the capture-site mode applies to all of them. Inbound trust uses
`TestInboundTrustVerifier` (TEST-01). Internal clients add the test peer header; the test's "public" client does not.

The application policy used in the suites registers these keys:

| Key | Codec | Wire name | Required | Loggable | Propagates to |
|---|---|---|---|---|---|
| `tenant` | string | `Tenant-Id` | yes | yes | `ATTACHED`, `DETACHED`, `INTERNAL_CALL`, `MESSAGE` |
| `priority` | long | `Priority` | no | no | `ATTACHED`, `DETACHED`, `INTERNAL_CALL` |

Test JWTs are signed with a key generated per run. User `alice` has the claim `tenant=acme`.

The suites, and the features whose scenarios they contain:

| Suite | Scenarios | Features |
|---|---|---|
| RuntimeConformanceIT | RC-1 – RC-9; runs in all three integration test tasks (BLD-06) | 002, 004 |
| ContextPropagationIT (IT-1) | IT-1.1 – IT-1.9 | 004 |
| TrustBoundaryIT (IT-2) | IT-2.1 – IT-2.9 | 003 |
| DeadlinePropagationIT (IT-3) | IT-3.1 – IT-3.8 | 005, 007 |
| AuthorityPropagationIT (IT-4) | IT-4.1 – IT-4.9 | 006, 007 |

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: `./gradlew check` is green locally and in CI with JDK 21 and JDK 25 installed.
- **SC-002**: The smoke test proves the JDK 25 run loads the versioned marker class from the JAR, and the JDK 21 run does not.
- **SC-003**: Every scenario ID listed in [roadmap.md](../../docs/roadmap.md#traceability) exists as a disabled test with the right `Pending: Mx`
  reason.

## Assumptions

- JDK 21 and JDK 25 are both installed locally, where Gradle toolchains detect them, and in CI, where `actions/setup-java` installs both and the
  workflow points Gradle at them. No toolchain resolver is configured, so Gradle does not download a missing JDK.
- Docker is available for Testcontainers wherever the integration tests run.
- Scenario bodies may stay empty until their feature starts, because the library API they use does not exist yet; BLD-10 only requires them to
  exist, compile and be disabled.
