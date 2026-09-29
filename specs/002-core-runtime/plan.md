# Implementation Plan: Core and Runtimes

**Branch**: `002-core-runtime` | **Date**: 2026-09-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-core-runtime/spec.md`, contract [contracts/core-api.md](contracts/core-api.md)

**Decisions**: the user decided Q1 – Q6 as recommended, and spec.md, the contract, 001's BLD-06 and `docs/roadmap.md` now say so. The gaps
G1 – G10 of the earlier drafts are resolved in the spec (see [Spec Gaps](#spec-gaps)). Two questions are still open for the user
([Open Decisions](#open-decisions)): G12, whether every public method rejects `null` arguments, which must be decided before T002; and G11,
what `Deadline.after` and `within` do with durations too large to represent, which must be decided before T017 and affects one test case there.

## Summary

The scope-only context model in plain Java, in `boundrail-context-core`: typed keys, immutable snapshots, bindings that rebind for one scope,
capture and explicit re-entry; one current-snapshot slot per thread, held by a ThreadLocal backend in the base classes and a ScopedValue backend
in the `java25` variant, selected through one class present in both, with a `thread-local` override; the four `Codecs`; the `ContextPolicy`
builder with validation, filtering and merging, and the fixed built-in rules in every built policy; and the built-in `Deadline`, `Authority` and
`Initiator` values with their keys, default policy and the rule that a deadline can only shrink.

The core API is driven by unit tests in core (which load classes from directories, so they always run the ThreadLocal backend). Each
RuntimeConformanceIT scenario is enabled as the failing test of the task that implements its behaviour, so it runs on the ThreadLocal backend in
all three integration test tasks at first. The ScopedValue task (T009) then enables RC-1 and re-runs every enabled scenario on `SCOPED_VALUE`
unchanged, which is what RT-06 asks.

## Technical Context

**Language/Version**: Java 21 (`main`, `--release 21`); Java 25 (`java25` source set, `--release 25`, packaged under `META-INF/versions/25/`)

**Primary Dependencies**: none at runtime (BLD-03); JUnit 6 and AssertJ for tests (already in `boundrail.java-conventions`)

**Storage**: N/A

**Testing**: unit tests in `boundrail-context-core/src/test/java`, run on the JDK 25 toolchain of `boundrail.java-conventions` with the
ThreadLocal backend only; RuntimeConformanceIT in `integration-tests` on `integrationTestJdk21`, `integrationTestJdk25` and
`integrationTestJdk25ThreadLocal` against the packaged JAR (BLD-06)

**Target Platform**: JVM 21+ (library)

**Project Type**: library (module `boundrail-context-core`, plus the conformance suite in `integration-tests`)

**Performance Goals**: `Context.capture()` cost independent of the number of bound keys (CORE-09): it returns the current reference

**Constraints**: no reflection on Java version strings (RT-02); `ScopedValue` only in `src/main/java25`; core logs only through
`System.Logger`; no Spring, no adapters, no scope listeners or capture sites, no RT-09 (those are 004)

**Scale/Scope**: 17 public types from the contract, 8 conformance scenarios, 23 implementation tasks and a close task

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|---|---|---|
| I Scope-only | Pass | No setter or in-place mutation (CORE-06); every change goes through `ContextBinding.run`/`call` or snapshot re-entry; restore in `finally` (CORE-07, RT-03) |
| II Key, policy, adapter separate; remote declared | Pass | `ContextKey` carries name, type and default only; rules live in `ContextPolicy`; unregistered keys stay in-process (POL-02, POL-03) |
| III Secure by default | Pass | `Authority` never prints its credential and has identity equality (ID-01); reserved wire names rejected (POL-09); built-in rules fixed and in every built policy (POL-06) |
| IV Deadlines only shrink | Pass | DL-10, DL-11, RC-8 |
| V Diagnostics report evidence | Pass | Only diagnostic in scope is the RT-04 warning for an unrecognised backend value, through `System.Logger` |
| VI Interoperate | N/A | No integrations in this feature |
| VII Spec-first, test-first | Pass | Every task is one red-green cycle; each RC scenario is enabled as its task's failing test; no TDD exceptions (below); the behaviour G1 – G10 needed is in the spec, not decided in the code; the open questions (G11, G12) are not coded until the spec answers them |
| VIII Simplicity | Pass | Only the contract's types; one internal slot interface with two implementations; no listeners, capture sites or Spring |
| Technical constraints | Pass | Core stays dependency-free (SC-003, checked in T024); ScopedValue only in `java25`; selection through one class in both variants (RT-02) |

## Research Notes

- **The `java25` source set must compile against `main`.** The final 001 review found that `java25` cannot see `main` classes. The Java 25
  variant of the selection class returns a `ContextSlot` and reads the backend override, both in `main`, so
  `build-logic/src/main/kotlin/boundrail.multi-release.gradle.kts` adds `compileClasspath += sourceSets.main.get().output` to the `java25`
  source set. **Decision: fold it into T009, not a TDD exception.** The change has no behaviour of its own; T009's failing check (RC-1 expects
  `SCOPED_VALUE` in `integrationTestJdk25`) cannot turn green without it, and T009's green step cannot compile without it. It is the minimum
  build code that failing test needs, exactly as T005 of 001 created the multi-release plugin in the green step of BLD-11.
- **Slot and selection.** An internal interface `internal.ContextSlot` (`backend()`, `current()`, and
  `<R, X extends Throwable> R call(ContextSnapshot, ContextCallable<R, X>) throws X`) is the single slot of RT-01. `ThreadLocalSlot` (base)
  saves the previous value, sets the new one, and restores it afterwards. In T002 (minimum code) it restores only after a normal return; T003
  moves the restore into `finally` and calls `remove()` when there was no previous value, i.e. when the outermost scope exits (RT-03). This is
  what makes RC-2's exception cases, RC-5's exceptional exit and the RT-03 unit test genuinely red in T003. `ScopedValueSlot` (`java25` only,
  package-private) uses `ScopedValue.where(CURRENT, snapshot).call(op::call)`, whose `CallableOp<T, X>` rethrows checked exceptions unchanged
  (CORE-08). The selection class `internal.SlotFactory` exists in both variants with the same signatures (a multi-release JAR must not change
  a class's API between versions): `public static ContextSlot create()` reads the override, and package-private `create(boolean
  forceThreadLocal)` makes the choice. The base variant always returns the ThreadLocal slot; the `java25` variant returns the ScopedValue slot
  unless the override forces ThreadLocal (RT-02, RT-04). `ContextRuntime` creates the slot once, in its static initialiser, which is the
  runtime's first use, as RT-04 now defines "at startup".
- **The override property.** `internal.BackendOverride` (base, package-private) turns the value of `boundrail.context.backend` into "force
  ThreadLocal" or "automatic". `thread-local` forces; unset and `auto` are automatic; anything else, compared exactly and case-sensitively
  (RT-04), so including `THREAD-LOCAL` and the empty string, is automatic and logs a `WARNING` through a `System.Logger` named
  `io.github.boundrail.context` (the logger name is an implementation choice). The parser takes the logger as a parameter so a unit test named
  after RT-04 can record the warning without restarting the JVM; the base variant also calls it, so an unrecognised value warns on Java 21 too.
- **RT-02 and RT-05 unit tests assert different things.** From class directories both would otherwise just see `THREAD_LOCAL`. The RT-02 test
  targets the selection class: the base `SlotFactory.create(forceThreadLocal)` gives the ThreadLocal slot whether or not the override forces it.
  The RT-05 test targets the public report: `ContextRuntime.backend()` returns the backend of the slot the runtime created (`THREAD_LOCAL` in
  unit tests). The `SCOPED_VALUE` half of RT-02 and RT-05 is proven only by RC-1 in T009, from the packaged JAR.
- **Why the RC scenarios are enabled task by task.** Unit tests load core from class directories and so only exercise the ThreadLocal slot.
  Enabling each RC scenario in the task that implements its behaviour makes it a real failing test on all three integration test tasks (it
  runs on ThreadLocal everywhere until T009). RC-1 is the exception: enabled in T009, after T008 has made `backend()` report `THREAD_LOCAL`,
  it is red only in `integrationTestJdk25`. T009 then switches `integrationTestJdk25` to the ScopedValue slot, and RC-2 – RC-7 must pass
  there unchanged. When an RC scenario is enabled, its `@DisplayName` becomes `RC-n <title>` with the title exactly as spec.md bolds it,
  without the trailing period (for example `RC-3 Snapshot run replaces the caller's snapshot`), its comment is updated to the spec text, and
  `@Disabled` is removed.
- **RC display names change on purpose.** 001 wrote the disabled RC-1 – RC-8 placeholders with lowercase display names (for example `RC-3
  snapshot run replaces the caller snapshot`). Enabling each scenario replaces that name with the spec's bold title, so the case and wording
  change deliberately; the reviewer should not flag it as an unrelated edit. RC-9 keeps its name until 004.
- **RC-5 and RC-7 are written so that they are red in their own task.** RC-5's body runs one scope that exits normally and one that exits by
  throwing on the same single-thread pool, and asserts the next task observes `EMPTY` after each; the exceptional exit is still "a scope
  exits" in RC-5's text, and it fails until T003's `finally`. The throwing scope must not kill the pool's worker: an exception escaping a
  task passed to `execute()` ends the worker thread, so the next task would run on a fresh thread, see `EMPTY`, and pass even without the
  `finally`. So the pooled task catches the scope's exception itself (or the task is submitted with `submit()`, whose `Future` holds the
  exception), and each task records `Thread.currentThread()`; the test asserts that the next task ran on the same `Thread` as the throwing
  one before asserting `EMPTY`. The RT-03 unit test in T003 is written the same way. RC-7 and the CORE-03 and CORE-04 unit tests assert the
  exception at the `with` call itself, without calling `run`; T002's `with` only records the change (its `Map.copyOf` would throw
  `NullPointerException` later, at `run`), so they fail until T004.
- **RT-03's `remove()` is not observable through the API.** A test can observe "no residue" (RC-5, and the RT-03 unit test after an
  exceptional exit on a pooled thread) but not `remove()` versus `set(null)`. The reviewer checks the `remove()` call against RT-03 in T003.
- **RC-1 JDK check (Q1).** 001's build already passes `boundrail.it.task=<task name>` to all three tasks
  (`build-logic/src/main/kotlin/boundrail.integration-tests.gradle.kts`, as the amended BLD-06 requires), so RC-1 needs no build change. It
  reads the property and fails (it does not skip) when it is missing or names an unknown task. It maps `integrationTestJdk21` to (21,
  `THREAD_LOCAL`), `integrationTestJdk25` to (25, `SCOPED_VALUE`) and `integrationTestJdk25ThreadLocal` to (25, `THREAD_LOCAL`), and asserts
  both `Runtime.version().feature()` and `ContextRuntime.backend()`. This is the only JDK check in the ThreadLocal task, whose filter excludes
  `BuildSmokeIT`. T009 proves it can fail by temporarily removing the override property, pointing a task at the other launcher, and dropping
  `boundrail.it.task`; each change is reverted.
- **Keys.** `ContextKey.of` requires a non-null name and type (CORE-03), then checks the name pattern, the reserved prefix and
  `type.isPrimitive()` (CORE-01; `void.class` is primitive too), and `requireNonNull` on the default (CORE-03). Built-in keys are created
  through a package-private factory that skips only the reserved-prefix check.
- **Snapshots.** A package-private immutable implementation of `ContextSnapshot` in `io.github.boundrail.context` wraps `Map.copyOf` of
  `ContextKey<?>` to value. `ContextKey` does not override `equals`, so keys compare by instance (CORE-02). `capture()` returns the current
  reference (CORE-09). `ContextSnapshot` stays a plain interface as the contract shows; `filter` and `merge` use only its interface methods.
- **Binding.** `ContextBinding` is an immutable list of changes (bind or remove, per key), applied in call order to the snapshot current when
  the binding runs, so the last change for a key wins (CORE-07) and DL-10 compares against that snapshot. From T004, `with` checks
  `Objects.requireNonNull(value)` and `key.type().cast(value)` at the call (CORE-03, CORE-04). `without(DEADLINE)` throws at the call, in both
  `Context.without` and `ContextBinding.without`, whether or not a deadline is current (DL-11). Because `without(DEADLINE)` always throws, a
  binding never removes `DEADLINE`, so the last-change rule and DL-10 do not interact beyond "a bound deadline is combined with the current one".
- **Codecs (WIRE-03).** Each codec is a `static final` constant, so every `Codecs` method returns the same instance; `encode` and `decode`
  `requireNonNull` their argument. `longValue()` checks `0|-?[1-9][0-9]*` over ASCII digits before `Long.parseLong`, whose
  `NumberFormatException` (a subclass of `IllegalArgumentException`) covers the out-of-range case. `uuid()` checks
  `[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}` before `UUID.fromString`, which alone would accept
  `1-2-3-4-5`. `locale()` checks one case-insensitive, precompiled regular expression transcribed from the ABNF of RFC 5646 §2.1
  (`langtag / privateuse / grandfathered`, with the 26 grandfathered tags listed) before `Locale.forLanguageTag`, which never throws on its own.
  The locale task is kept separate because that expression is the largest single piece of review in US2.
- **`ja_JP_JP` round-trips on JDK 25.** On JDK 25, `toLanguageTag()` of `ja_JP_JP` is `ja-JP-u-ca-japanese-x-lvariant-JP` (not
  `ja-JP-u-ca-japanese`, as an earlier draft said), and `Locale.forLanguageTag` of that tag gives back a locale equal to `ja_JP_JP`, so WIRE-03
  lists it as valid. Unit tests run only on the JDK 25 toolchain, so `CodecsTest` pins the JDK 25 behaviour of the examples; it asserts
  `encode(l)` equals `l.toLanguageTag()` and that `decode` gives back `l`, not a literal tag. WIRE-03 defines validity through the running JDK's
  own `Locale` methods, so the rule itself does not depend on the version.
- **Policy.** Built policies hold an immutable map from key to a rules record (codec, wire name, required, loggable, `Set.copyOf` of
  boundaries). `codec(key)` and `wireName(key)` read back the registered values and are empty when none is registered (POL-01). The builder
  keeps the first registration of each key; a later registration of the same key is compared with it and either dropped (equal) or rejected at
  `build()` (conflict), as POL-05 says; `merge` applies the same comparison per key. Rule-set equality compares codecs with `==` (so
  `Codecs.string()` twice is equal, two separately written codecs are not), wire names exactly, flags and boundary sets with `equals`.
  Different keys conflict when their names are equal, or their wire names are equal ignoring case (`toLowerCase(Locale.ROOT)`). So for one key,
  `X-Tenant` and `x-tenant` are conflicting rule sets; for two keys, they are conflicting wire names. POL-03's codec and wire-name check and
  POL-09's reserved names apply to application keys only.
- **Built-in rules (POL-03, POL-06).** `BuiltInKeys.defaultPolicy()` holds only the three built-in rules, built through a package-private path
  that skips POL-03's check, POL-09 and POL-06's rejection. None has a codec; `DEADLINE` has the wire name `Boundrail-Deadline-Ms`,
  `INITIATOR` has `Boundrail-Initiator` (the WIRE-02 header names), and `AUTHORITY` has none. From T023, `builder().build()` rejects a built-in
  key and adds the built-in rules to every policy it returns. Built-in rules are the same record instances in every policy, so `merge` finds
  them equal (POL-05, POL-06). Until T023, built policies hold application rules only; no earlier test depends on built-in keys in a built
  policy.
- **Deadline.** A `long` instant from `System.nanoTime()`; `remaining()` is `max(0, instant - now)`; expired when `remaining()` is zero, so
  `Deadline.after(Duration.ZERO)` and any negative duration are expired at once (DL-01). `earliest` compares instants with `a - b < 0`
  (overflow-safe for `nanoTime`) and returns `this` unless `other` is strictly earlier; on a tie either is allowed (DL-01), so no test pins it
  and no test-only factory exists. `within(max)` is `earliest(Deadline.after(max))`. `after`, `earliest` and `within` `requireNonNull` their
  argument (CORE-03). DL-10 uses `current.earliest(bound)`. Unit tests avoid sleeps: they use zero, negative and long (one hour) budgets.
  RC-8 asserts "at most" bounds only. Durations whose nanoseconds overflow `long`, or whose instant would overflow, are left to the open
  decision G11; T017 does not test them and adds no handling for them.
- **Initiator.** Value equality, `hashCode` of the value, and `toString()` returning the value (ID-07). Length is `String.length()`, so 128
  supplementary characters (256 chars) are accepted and 129 (258 chars) are rejected, which a code-point count would accept. A snapshot holding
  an initiator therefore prints it (CORE-12), while an authority still prints redacted (ID-01).
- **Verify commands.** Gradle applies `--tests` only to the task just before it on the command line, so a filter meant for several tasks is
  repeated after each. The conformance command used throughout is:

  ```bash
  ./gradlew --continue :integration-tests:integrationTestJdk21 --tests '*RuntimeConformanceIT' \
            :integration-tests:integrationTestJdk25 --tests '*RuntimeConformanceIT' \
            :integration-tests:integrationTestJdk25ThreadLocal
  ```

  The ThreadLocal task needs no `--tests`, because its build filter already restricts it to `*RuntimeConformanceIT`. `--continue` makes a
  red run execute all three tasks and report the failure in each, instead of stopping at the first failing task. Unit commands with more
  than one class repeat `--tests` after the one `test` task, which is allowed.

## Spec Gaps

G1 – G10 of the earlier drafts are resolved in spec.md, the contract, 001's BLD-06 and the roadmap. None blocks a task.

| # | Gap | Resolution in the spec | Planned in |
|---|---|---|---|
| G1 | Built-in `DEADLINE` and `INITIATOR` list remote boundaries without an M1 codec | POL-03: the codec and wire-name check applies to application keys only; built-in rules have no codec; `DEADLINE` and `INITIATOR` have the WIRE-02 header names, `AUTHORITY` none | T022, T023 |
| G2 | WIRE-03 did not define valid and invalid input per codec | WIRE-03 defines each codec's input with examples; `Codecs` methods return singletons; `null` throws `NullPointerException` | T010, T011, T012 |
| G3 | POL-05 did not define a rule-set conflict or equal re-registration | POL-05 defines the conflict; equal re-registration is accepted once | T014, T016, T023 |
| G4 | No requirement for `Deadline.earliest` and `within` | DL-01 specifies both; on a tie `earliest` may return either; zero or negative durations are already expired | T017 |
| G5 | A binding that changes one key twice | CORE-07: the last change for a key wins | T003 |
| G6 | `Initiator` equality and `toString()` | ID-07 and the contract: value equality, `toString()` is the value; length is `String.length()` | T021 |
| G7 | `ContextKey.of` with a primitive type | CORE-01 rejects it with `IllegalArgumentException` | T001 |
| G8 | SC-002 needed a test named RT-06 | SC-002 exempts RT-06 (Q3) | T024 |
| G9 | `null` arguments to the value factories | CORE-03: `ContextKey.of`, `Initiator.of`, `Authority.of`, `Deadline.after`, `earliest` and `within` throw `NullPointerException` | T001, T017, T020, T021 |
| G10 | RT-09 tagged `[M1]` | Moved to 004 `[M3]` in spec.md and the roadmap (Q2) | none; T024 confirms 002 no longer lists it |

Remaining gaps, for the spec-creator:

| # | Gap | Effect on the plan |
|---|---|---|
| G11 | DL-01 does not say what `Deadline.after` and `within` do with a duration too large to represent (`Duration.toNanos()` overflows `long`, or `nanoTime() + nanos` overflows). Open for the user: cap it at the largest representable deadline, or throw `IllegalArgumentException`; the spec-creator recommends the cap. | Blocks only the huge-duration case, in T017 (`Deadline.after`, `within`; DL-10 and RC-8 reuse them but use small budgets). The rest of T017 proceeds. If the spec answers before T017 starts, one test (`DL-01 …` for the chosen behaviour, for both methods) joins T017; otherwise it becomes one new red-green task right after T017. Until then the code adds no handling. |
| G12 | CORE-03 lists the calls that reject `null` arguments, but not the rest of the API: `Context.with(null, v)`, `without(null)`, `key` arguments to `find`, `run(null)`/`call(null)`/`wrap(null)`, the policy builder's arguments, `propagates`, `filter` and `merge` with `null`. Under the planned design several of these do not fail at all, or fail late and misleadingly: `Context.without(null)` and `ContextBinding.without(null)` record a removal of no key and are a silent no-op; `snapshot.wrap(null)` returns a wrapper that fails later, on the thread that runs it; `propagates(unregisteredKey, null)` returns `false` and `filter(snapshot, null)` drops unregistered keys silently (both fall through POL-02's boundary check); `KeyRules.codec(null)` and `wireName(null)` are likely stored as "none", so `build()` reports a misleading POL-03 failure (missing codec or wire name) instead of the `null`. Open for the user; the reviewer suggests the rule "IF `null` is passed as any argument of a public method or factory of the library, THEN that call SHALL throw `NullPointerException`." | Must be decided before T002. First affected tasks: T002 (a `null` key to `Context.with` and `ContextBinding.with`; `ContextBinding.run(null)`), T003 (`Context.without`, `ContextBinding.without`, `ContextBinding.call(null)`), T005 (`ContextSnapshot.run`, `call`), T006 (both `wrap` methods), T007 (`ContextSnapshot.find`), T013 (`Builder.key`, `KeyRules.codec`, `wireName`, `propagateTo`, and the `propagates`, `isRequired`, `isLoggable`, `wireName` and `codec` queries), T015 (`filter`), T016 (`merge`). The codecs (T010 – T012) are already covered by WIRE-03 and the value factories by CORE-03. If the user adopts the rule, each affected task gains one `null` test plus a `requireNonNull`; the task shapes do not change. Until then the code adds no checks. |

## TDD Exceptions

None. Every task starts with a failing test. The one build change (the `java25` compile classpath) is folded into T009, whose failing RC-1 test
it helps turn green (see [Research Notes](#research-notes)). RC-1 needs no build change for `boundrail.it.task`, which 001 already passes.
There is nothing for the user to approve in this table.

| Task | What | Verified by |
|---|---|---|
| none | | |

## Open Decisions

| Decision | Options | Tasks affected | Effect |
|---|---|---|---|
| G11 huge durations for `Deadline.after` and `within` | cap at the largest representable deadline (spec-creator's recommendation), or `IllegalArgumentException` | T017 (the huge-duration case only); decide before T017 | Either way one test and a few lines in `Deadline.after`; `within` follows because it is `earliest(after(max))`. Not decided here. |
| G12 `null` arguments to the rest of the public API | the reviewer's rule "IF `null` is passed as any argument of a public method or factory of the library, THEN that call SHALL throw `NullPointerException`", or leave the behaviour unspecified | T002, T003 (`with`, `without`), T005, T006, T007 (`run`, `call`, `wrap`, `find`), T013, T015, T016 (builder, `KeyRules`, queries, `filter`, `merge`); decide before T002 | With the rule, each affected task gains one `null` test and a `requireNonNull`; the task shapes do not change. Without it, the silent and late failures listed in [Spec Gaps](#spec-gaps) stay. Not decided here. |

## Project Structure

### Documentation (this feature)

```text
specs/002-core-runtime/
├── spec.md
├── plan.md                # this file
├── tasks.md
└── contracts/core-api.md
```

### Source Code (repository root)

```text
build-logic/src/main/kotlin/boundrail.multi-release.gradle.kts     # T009: java25 compiles against main
boundrail-context-core/
├── src/main/java/io/github/boundrail/context/
│   ├── ContextKey.java  Context.java  ContextBinding.java  ContextCallable.java
│   ├── ContextSnapshot.java  ImmutableContextSnapshot.java (package-private)  MissingContextException.java
│   ├── ContextRuntime.java
│   ├── Boundary.java  ContextCodec.java  Codecs.java  ContextPolicy.java
│   ├── BuiltInKeys.java  Deadline.java  Deadlines.java  DeadlineExceededException.java  Authority.java  Initiator.java
│   └── internal/
│       ├── ContextSlot.java  ThreadLocalSlot.java  BackendOverride.java  SlotFactory.java
│       └── RuntimeVersionMarker.java                               # exists (BLD-11)
├── src/main/java25/io/github/boundrail/context/internal/
│   ├── SlotFactory.java  ScopedValueSlot.java
│   └── RuntimeVersionMarker.java                                   # exists (BLD-11)
└── src/test/java/io/github/boundrail/context/
    ├── ContextKeyTest.java  ContextBindingTest.java  ContextSnapshotTest.java  ContextRuntimeTest.java
    ├── CodecsTest.java  ContextPolicyTest.java  ContextPolicyValidationTest.java
    ├── DeadlineTest.java  DeadlineScopeTest.java  BuiltInKeysTest.java  AuthorityTest.java  InitiatorTest.java
    └── internal/BackendOverrideTest.java  internal/SlotFactoryTest.java
integration-tests/src/test/java/io/github/boundrail/context/it/RuntimeConformanceIT.java   # RC-1 – RC-8 enabled
```

**Structure Decision**: Everything lives in `boundrail-context-core`, in the base package the contract names; implementation classes that are
not in the contract go to `internal` or stay package-private. The ScopedValue slot is the only new class in `java25`. The conformance suite
stays in `integration-tests`, the only project that loads the packaged multi-release JAR.

## Requirement Coverage

| Requirement or scenario | Tasks |
|---|---|
| CORE-01 | T001, T018 |
| CORE-02 | T002 |
| CORE-03 | T001, T004, T017, T020, T021 (the rest of the API: G12) |
| CORE-04 | T004 |
| CORE-05 | T002 |
| CORE-06 | T003, T007 |
| CORE-07 | T002, T003 |
| CORE-08 | T003 |
| CORE-09, CORE-11 | T005 |
| CORE-10 | T005, T006 |
| CORE-12 | T007, T020, T021 |
| RT-01 | T002 |
| RT-02, RT-04, RT-05 | T008, T009 |
| RT-03 | T003 |
| RT-06 | T009 (every enabled scenario on both backends), T024 (SC-001; exempt from SC-002) |
| POL-01, POL-02 | T013 |
| POL-03 | T013, T014, T022 |
| POL-04 | T015 |
| POL-05 | T014, T016, T023 |
| POL-06 | T022, T023 |
| POL-09 | T014 |
| WIRE-03 | T010, T011, T012 |
| DL-01 | T017 (huge durations: G11) |
| DL-02 | T018, T022 |
| DL-10, DL-11 | T019 |
| ID-01 | T020 |
| ID-07 | T021 |
| BLD-06 (`boundrail.it.task`, read by RC-1) | T009 |
| RC-1 | T009 |
| RC-2, RC-5 | T003 |
| RC-3, RC-4 | T005 |
| RC-6 | T002 |
| RC-7 | T004 |
| RC-8 | T019 |
| SC-001 – SC-003 | T024 |

RT-09 is no longer in this feature (004 `[M3]`).

## Risks

Backend differences in exception handling and nested scopes (roadmap M1). The conformance suite exists to catch exactly this: T009 is where the
enabled scenarios first run on `SCOPED_VALUE`, so a failure there is debugged to its root cause (systematic-debugging) before any fix, and RC-2's
checked-exception case is the most likely place for it.

## Complexity Tracking

No constitution violations to justify.
