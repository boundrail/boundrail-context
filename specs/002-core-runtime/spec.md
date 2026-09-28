# Feature Specification: Core and Runtimes

**Feature Branch**: `002-core-runtime`

**Created**: 2026-09-28

**Status**: Draft

**Milestone**: M1

**Input**: Roadmap milestone [M1](../../docs/roadmap.md#m1--core-and-runtimes).

API signatures: [contracts/core-api.md](contracts/core-api.md).

## User Scenarios & Testing *(mandatory)*

The users are application developers who read and bind context in plain Java, and the adapter authors of later features.

### User Story 1 - Scoped, typed context on both runtimes (Priority: P1)

A developer declares typed keys, binds values for a lexical scope, reads them anywhere in the call stack, and captures a snapshot to re-enter
elsewhere. The behaviour is identical on the ThreadLocal backend (Java 21–24) and the ScopedValue backend (Java 25+).

**Why this priority**: Every other feature is built on the scope-only model.

**Independent Test**: RuntimeConformanceIT RC-1 – RC-7 in all three integration test tasks.

**Acceptance Scenarios** (RuntimeConformanceIT, runs in all three integration test tasks, BLD-06):

1. **RC-1** The active backend is `SCOPED_VALUE` under `integrationTestJdk25`, and `THREAD_LOCAL` under `integrationTestJdk21` and
   `integrationTestJdk25ThreadLocal`. *(RT-02, RT-04, RT-05)*
2. **RC-2** Nested bindings rebind and remove keys; each scope exit restores the exact previous snapshot, including when the operation throws a
   runtime exception or a checked exception. *(CORE-07, CORE-08)*
3. **RC-3** `snapshot.run` replaces the caller's snapshot rather than merging with it, and restores it afterwards. *(CORE-10)*
4. **RC-4** One snapshot run concurrently on 1,000 virtual threads makes the same values visible on every thread, and bindings made inside those
   threads never become visible to the others. *(CORE-11)*
5. **RC-5** After a scope exits on a pooled platform thread, the next task on that thread observes `EMPTY`. *(RT-03)*
6. **RC-6** `get()` on an unbound key without a default throws `MissingContextException` naming the key; with a default, it returns the default and
   `isBound()` is false. *(CORE-05)*
7. **RC-7** Binding `null` throws `NullPointerException`, and binding a value of the wrong type throws `ClassCastException` at the binding call.
   *(CORE-03, CORE-04)*

---

### User Story 2 - Propagation policy (Priority: P2)

A developer declares, per key, a codec, a wire name, whether it is required or loggable, and which boundaries it crosses. The library validates the
policy when it is built and can filter a snapshot for any boundary.

**Why this priority**: Adapters in features 003–007 consult the policy at every boundary, but the policy itself is testable in isolation.

**Independent Test**: Unit tests in `boundrail-context-core`.

**Acceptance Scenarios**: Covered by unit tests whose display names start with the requirement IDs (POL-01 – POL-06, POL-09, WIRE-03). No
acceptance-suite scenario.

---

### User Story 3 - Built-in deadline, authority and initiator values (Priority: P3)

The library ships three built-in keys with fixed policies. A deadline can be shortened by a nested scope but never extended or removed. An authority
never reveals its credential.

**Why this priority**: Features 005 and 006 give these values their boundary behaviour; this story only provides the types and their local rules.

**Independent Test**: RC-8, plus unit tests for DL-01, DL-02, ID-01 and ID-07.

**Acceptance Scenarios**:

1. **RC-8** Within a scope whose deadline has 100ms remaining, binding a deadline of 10s leaves at most 100ms remaining, binding one of 10ms shortens
   it to at most 10ms, and `Context.without(DEADLINE)` throws `IllegalArgumentException`. *(DL-10, DL-11)*

---

### Edge Cases

- Invalid or reserved key names: CORE-01. Null values: CORE-03. Wrong value types: CORE-04.
- Conflicting keys, wire names or rule sets across merged policies: POL-05. Reserved wire names: POL-09. Built-in keys re-registered: POL-06.
- An unrecognised `boundrail.context.backend` value: RT-04.

## Requirements *(mandatory)*

### Core API

**CORE-01** `[M1]` Key names SHALL match `[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)*`. The prefix `boundrail.` is reserved for built-in keys. IF a key is
created with an invalid or reserved name, THEN `ContextKey.of` SHALL throw `IllegalArgumentException`.

**CORE-02** `[M1]` Key equality SHALL be by instance. Two keys with the same name are different keys; conflicts between them are rejected by the
policy (POL-05).

**CORE-03** `[M1]` Context values SHALL be non-null. IF `null` is passed as a value or default, THEN the library SHALL throw `NullPointerException`.
Values SHOULD be immutable; the library does not copy them.

**CORE-04** `[M1]` IF a value is not an instance of the key's type, THEN binding it SHALL throw `ClassCastException` at the binding call (not at read
time).

**CORE-05** `[M1]` `key.find()` SHALL return the value bound in the current snapshot, else the key's default, else empty. `key.get()` SHALL return the
same value, or throw `MissingContextException` identifying the key when `find()` would be empty. `key.isBound()` SHALL be true only if the key is
bound in the current snapshot.

**CORE-06** `[M1]` The public API SHALL NOT offer any operation that mutates the current snapshot in place.

**CORE-07** `[M1]` WHEN a `ContextBinding` is run, the library SHALL execute the operation with a current snapshot equal to the previous current
snapshot with the binding's additions, replacements and removals applied. It SHALL restore the previous snapshot when the operation completes, whether
normally or exceptionally. Bindings of `DEADLINE` are subject to DL-10 and DL-11.

**CORE-08** `[M1]` `call` SHALL return the operation's result and rethrow its exceptions unchanged, including checked exceptions of type `X`.

**CORE-09** `[M1]` `Context.capture()` SHALL return the current snapshot, or `ContextSnapshot.EMPTY` if none is current, without copying. Its cost
SHALL be independent of the number of bound keys.

**CORE-10** `[M1]` WHEN `snapshot.run(op)` or `snapshot.call(op)` is invoked, the library SHALL execute `op` with exactly that snapshot as current,
replacing (not merging with) the caller's snapshot, and SHALL restore the caller's snapshot afterwards. `wrap` SHALL return an operation that behaves
like `run`/`call` when executed later, on any thread.

**CORE-11** `[M1]` Snapshots SHALL be immutable and safe to share across threads, including being run concurrently on many threads.

**CORE-12** `[M1]` `ContextSnapshot.toString()` SHALL list key names with each value's `toString()`. (Built-in values redact themselves; see ID-01.)

### Runtime backends

**RT-01** `[M1]` The runtime SHALL hold the current snapshot in a single slot per thread of execution.

**RT-02** `[M1]` On Java 21–24 the runtime SHALL use a ThreadLocal backend. On Java 25+ it SHALL use a `ScopedValue` backend. Selection SHALL happen
through a class that exists in both the base JAR and `META-INF/versions/25/`, without reflection on Java version strings.

**RT-03** `[M1]` The ThreadLocal backend SHALL restore the previous snapshot in a `finally` block, and SHALL call `ThreadLocal.remove()` when the
outermost scope on a thread exits, so that pooled threads carry no residue.

**RT-04** `[M1]` WHERE the system property `boundrail.context.backend=thread-local` is set at startup, the runtime SHALL use the ThreadLocal backend
on any Java version. Any other value except `auto` SHALL cause a warning, and the runtime SHALL use automatic selection.

**RT-05** `[M1]` `ContextRuntime.backend()` SHALL report the active backend.

**RT-06** `[M1]` Both backends SHALL pass RuntimeConformanceIT (the RC scenarios in this spec and RC-9 in
[004-async-diagnostics](../004-async-diagnostics/spec.md)) unchanged.

**RT-09** `[M1]` The only JVM-wide library state SHALL be the current-snapshot slot, the registered scope listeners and the capture-site mode. Every
other piece of state, including the effective policy, violation reporting and its rate limiter, and metrics, SHALL belong to a single Spring
application context.

### Policy

Built-in default policies:

| Key | ATTACHED | DETACHED | INTERNAL_CALL | EXTERNAL_CALL | MESSAGE | Required | Loggable | Wire name |
|---|---|---|---|---|---|---|---|---|
| `boundrail.deadline` | ✓ | ✗ | ✓ | ✗ (applied, DL-07) | ✗ | no | no | `Boundrail-Deadline-Ms` |
| `boundrail.authority` | ✓ | ✗ | ✓ (via propagator) | never | never | no | never | n/a (`Authorization`) |
| `boundrail.initiator` | ✓ | ✓ | ✓ | ✗ | ✓ | no | yes | `Boundrail-Initiator` |
| unregistered keys | ✓ | ✓ | ✗ | ✗ | ✗ | no | no | none |

**POL-01** `[M1]` `ContextPolicy` SHALL be built with a builder that registers, per key, a codec, a wire name, a required flag, a loggable flag and
the set of boundaries the key propagates to. Built policies SHALL be immutable.

**POL-02** `[M1]` A key not registered in a policy SHALL propagate to `ATTACHED` and `DETACHED` only, and SHALL be neither required nor loggable.

**POL-03** `[M1]` A key SHALL propagate to `INTERNAL_CALL`, `EXTERNAL_CALL` or `MESSAGE` only if the policy lists that boundary for it. IF a policy
lists a remote boundary for a key without both a codec and a wire name, THEN `build()` SHALL fail with `IllegalStateException`. `AUTHORITY` is exempt,
because it is carried by the `AuthorityPropagator` (ID-03).

**POL-04** `[M1]` `filter(snapshot, boundary)` SHALL return a snapshot containing exactly the bindings whose keys propagate to `boundary`.

**POL-05** `[M1]` IF a policy, or the result of `merge`, contains two keys with the same name, two keys with the same wire name (compared
case-insensitively), or two conflicting rule sets for the same key, THEN it SHALL fail with `IllegalStateException`.

**POL-06** `[M1]` The built-in default policy (table above) SHALL always be merged into the effective policy. Built-in rules are fixed in v0.1: IF an
application policy registers a built-in key, THEN policy building SHALL fail with `IllegalStateException`.

**POL-09** `[M1]` Wire names are reserved for the library if they equal `Authorization` or start with `Boundrail-`, compared case-insensitively. IF an
application policy uses a reserved wire name, THEN `build()` SHALL fail with `IllegalStateException`.

**WIRE-03** `[M1]` The built-in codecs SHALL round-trip every valid value, and SHALL reject invalid input with `IllegalArgumentException`.

### Built-in values

**DL-01** `[M1]` `Deadline` SHALL be an immutable value based on a monotonic clock (`System.nanoTime`). `remaining()` SHALL never be negative. A
deadline is expired when its remaining time is zero, so `Deadline.after(Duration.ZERO)` is expired immediately. `check()` SHALL throw
`DeadlineExceededException` with stage `APPLICATION` when the deadline has expired.

**DL-02** `[M1]` `BuiltInKeys.DEADLINE` SHALL use the built-in default policy (table above), and `Deadlines.current()` SHALL be equivalent to
`BuiltInKeys.DEADLINE.find()`.

**DL-10** `[M1]` WHEN a binding binds `DEADLINE` while a deadline is current, the resulting snapshot SHALL contain the earlier of the current and the
new deadline. A child operation can shorten a deadline but never extend it.

**DL-11** `[M1]` IF a binding removes `DEADLINE` with `without`, THEN the library SHALL throw `IllegalArgumentException`. Work that must outlive the
request's deadline SHALL use a detached boundary (EXE-02) or explicit snapshot re-entry (CORE-10), both of which replace the context rather than
extend it.

**ID-01** `[M1]` `Authority.toString()`, and any string representation the library produces of an authority, SHALL NOT contain the credential.
`equals` and `hashCode` SHALL be identity-based, so the library never compares or hashes credentials.

**ID-07** `[M1]` `Initiator` values SHALL be non-empty and at most 256 characters long. Conventional prefixes are `user:`, `service:` and `system:`.

### Configuration

Properties use the prefix `boundrail.context.`.

| Property | Default | Meaning |
|---|---|---|
| `backend` (JVM system property) | `auto` | `auto` or `thread-local` (RT-04) |

### Key Entities

- **ContextKey**: name, type and optional default; identity by instance.
- **ContextSnapshot**: immutable set of key/value bindings; exactly one is current at any point of execution.
- **ContextBinding**: an immutable, pending set of additions, replacements and removals applied for one scope.
- **ContextPolicy**: per-key rules (codec, wire name, required, loggable, boundaries); immutable once built.
- **Deadline, Authority, Initiator**: the built-in value types.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: RC-1 – RC-8 are enabled and green in `integrationTestJdk21`, `integrationTestJdk25` and `integrationTestJdk25ThreadLocal`.
- **SC-002**: Every requirement in this spec has a unit or conformance test whose display name starts with its ID.
- **SC-003**: `boundrail-context-core` still has no runtime dependencies (BLD-03).

## Assumptions

- 001-foundation is complete: the multi-release build and the three integration test tasks exist.
- Boundary behaviour of the built-in keys (HTTP, executors, Kafka) is out of scope here and belongs to features 003–007.
