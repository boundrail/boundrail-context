---
description: "Task list for 002-core-runtime"
---

# Tasks: Core and Runtimes

**Input**: Design documents from `/specs/002-core-runtime/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [contracts/core-api.md](contracts/core-api.md)

**Tests**: Required (constitution principle VII). Each task is one red-green cycle: its failing test, with the behaviour it pins down, and the
minimum code that makes it pass, committed and reviewed together. Every task leaves `./gradlew check` green. There are no TDD exceptions in this
feature (see [plan.md](plan.md#tdd-exceptions)).

**Decisions**: the user decided OD-1 and OD-2; see [plan.md](plan.md#decisions). OD-1 approves the CORE-03 `null`-check precedence
(affects T001 only). OD-2 keeps `Builder.key` running its rules consumer once, before `key` returns, as plan design for 002, with nothing
added to the spec (no test depends on it; T013). G11
(the deadline cap) and G12 – G16 (the `null` rule of CORE-03) are decided in the spec and planned below:

- G12: a `null` argument to a method or factory the contract declares throws `NullPointerException` at that call, before the call's other
  argument checks (OD-1).
- G13: `equals(Object)` returns `false` for `null` (T021 asserts it for `Initiator`); methods inherited from JDK types are not covered, so
  `ContextKey` (T001) and `Authority` (T020) get no `equals` override.
- G14: a `null` varargs element, e.g. `KeyRules.propagateTo(ATTACHED, null)`, throws `NullPointerException` at the call (T013).
- G15: only the library's own implementations are bound, not application-written `ContextSnapshot`, `ContextCodec`, `Builder` or
  `KeyRules` implementations; no task changes.
- G16: public classes in `internal` are not covered, so `ThreadLocalSlot.call` (T002) and `ScopedValueSlot.call` (T009) get no `null`
  check.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: The user story the task belongs to (US1–US3)

Paths: `core/` is `boundrail-context-core/src/main/java/io/github/boundrail/context/`, `core25/` is
`boundrail-context-core/src/main/java25/io/github/boundrail/context/`, `test/` is `boundrail-context-core/src/test/java/io/github/boundrail/context/`,
and `RuntimeConformanceIT` is `integration-tests/src/test/java/io/github/boundrail/context/it/RuntimeConformanceIT.java`.

Verify commands:

- **Unit**: `./gradlew :boundrail-context-core:test --tests '<TestClass>'` (with `--tests` repeated for each class) for red and green, then
  `./gradlew check` before commit.
- **RC**: the conformance run, then `./gradlew check` before commit. `--tests` applies only to the task just before it, so it is repeated
  after each task:

  ```bash
  ./gradlew --continue :integration-tests:integrationTestJdk21 --tests '*RuntimeConformanceIT' \
            :integration-tests:integrationTestJdk25 --tests '*RuntimeConformanceIT' \
            :integration-tests:integrationTestJdk25ThreadLocal
  ```

  (The ThreadLocal task's build filter already restricts it to `*RuntimeConformanceIT`. `--continue` makes a red run report the failure in
  every task rather than stop at the first.)

Enabling an RC scenario means: remove `@Disabled`, set `@DisplayName` to `RC-n <title>` with the title exactly as spec.md bolds it, without the
trailing period, update the comment to the spec text, and write the body. This deliberately replaces 001's lowercase placeholder names (see
[plan.md](plan.md#research-notes)). It must fail (red) before the green step, in all three integration test tasks, except RC-1, which T009
enables after T008 and which is red only in `integrationTestJdk25`.

## Phase 1: Setup

None. 001-foundation built the core project, its test dependencies, the three integration test tasks and the `boundrail.it.task` property
(BLD-06). The one build change this feature needs (the `java25` compile classpath) belongs to T009.

---

## Phase 2: User Story 1 - Scoped, typed context on both runtimes (Priority: P1) 🎯 MVP

**Goal**: Keys, bindings, snapshots and re-entry, identical on the ThreadLocal and ScopedValue backends.

**Independent Test**: The RC command above: RC-1 – RC-7 green in all three integration test tasks.

- [x] T001 [US1] Keys. Red: `test/ContextKeyTest.java` with `CORE-01 accepts names matching the key name pattern`, `CORE-01 rejects invalid
      names` (parameterised: empty, upper case, leading digit, trailing or double dot), `CORE-01 rejects the reserved boundrail. prefix`,
      `CORE-01 rejects primitive types` (parameterised: `long.class`, `int.class`, `boolean.class`, in both `of` factories), `CORE-03 rejects a
      null name or type with NullPointerException` (both factories), `CORE-03 rejects a null default` and `CORE-03 a null argument takes
      precedence over CORE-01 checks` (parameterised, each asserting `NullPointerException`: `of("boundrail.x", String.class, null)`, the
      spec's example; `of("Bad", null)`; `of("boundrail.x", null)`; `of("x", long.class, null)`; `of(null, long.class)`, where the
      `null` name wins over the primitive-type check; OD-1, decided); `name()`, `type()` and `defaultValue()` return what was given.
      Green: `core/ContextKey.java` with both `of` factories (`requireNonNull` on each argument, the default included, before the
      CORE-01 checks) and the three accessors, no `equals` override (the inherited `Object.equals` is not covered by CORE-03, G13)
      (CORE-01, CORE-03). Verify: Unit `ContextKeyTest`
- [x] T002 [US1] Bind and read. Red: `test/ContextBindingTest.java` with `CORE-05 find returns the bound value, else the default, else empty`,
      `CORE-05 get throws MissingContextException naming the key`, `CORE-05 get returns the bound value, else the default`,
      `CORE-05 isBound ignores defaults`, `CORE-07 run binds for the scope and restores afterwards` (also pinning the merge: inside
      `Context.with(TENANT, "t1").run(…)`, a nested `Context.with(REGION, "us")` sees TENANT `t1` and REGION `us`; red for a `run` that
      replaces the current snapshot instead of applying its changes to it), `CORE-02 keys with the same name are different keys` (two
      same-named keys bound in one binding keep separate values), `RT-01 each thread has its own current snapshot`,
      `CORE-03 with rejects a null key with NullPointerException at the with call` (for `Context.with` and `ContextBinding.with`, without calling
      `run`), `CORE-03 run rejects a null operation with NullPointerException and leaves the current snapshot unchanged` (with a binding that
      changes the snapshot: `Context.with(k, v).run(null)` throws, then `k.isBound()` is `false`; red without the check: the scope is
      entered, and with no `finally` yet it stays bound; the `Context.capture()` assertion is added in T005, which creates `capture`); enable
      RC-6 in `RuntimeConformanceIT`. Green: `core/Context.java`
      (`with`), `core/ContextBinding.java` (`with` checks only that the key is not `null` and records the change; `run`, which checks its operation
      first), `core/ContextSnapshot.java` (`EMPTY`), `core/ImmutableContextSnapshot.java`, `core/MissingContextException.java`,
      `core/ContextCallable.java`, `core/ContextRuntime.java` (slot holder only; `backend()` comes in T008), `core/internal/ContextSlot.java`,
      `core/internal/ThreadLocalSlot.java` (restores the previous snapshot after a normal return only; `finally` comes in T003),
      `core/internal/SlotFactory.java` (base: ThreadLocal), and `find`/`get`/`isBound` on `ContextKey` (CORE-02, CORE-03, CORE-05, CORE-07, RT-01;
      RC-6). No `null` check in `internal`: `ThreadLocalSlot.call` gets none (G16). Verify: Unit `ContextBindingTest`; RC
- [x] T003 [US1] Removal, nesting, exceptions and `call`. Red: in `ContextBindingTest`, `CORE-07 without removes a key for the scope`, `CORE-07
      the last change for a key wins within one binding` (`with(k, 1).without(k)` leaves `k` unbound; `without(k).with(k, 2)` and
      `with(k, 1).with(k, 2)` bind 2), `CORE-07 nested bindings restore the exact previous snapshot` (asserted per key: after the nested
      scope exits, `find()` returns the outer scope's values again, and keys only the inner scope added or removed are back to their outer
      state; the `Context.capture()` assertion is added in T005), `CORE-07 restores the previous snapshot when the operation throws` (per
      key, as above), `CORE-08 call returns the result and rethrows checked exceptions unchanged`, `CORE-06 with and without return a new
      binding`, `RT-03 a pooled thread carries no residue after a scope exits exceptionally` (the next task on the same thread asserts that
      each key the previous scope bound is not bound, `isBound()` `false`; the `Context.capture()` assertion is added in T005), `CORE-03
      without rejects a null key with NullPointerException at the without call` (for `Context.without` and `ContextBinding.without`; red
      without the check, which records a silent no-op removal), `CORE-03 call rejects a null operation with NullPointerException and leaves
      the current snapshot unchanged` (with a binding that changes the snapshot, as in T002: afterwards `k.isBound()` is `false`; the
      `Context.capture()` assertion is added in T005); enable RC-2 and RC-5. RC-2 asserts per key, as the nested-restore unit test does.
      RC-5's body runs, on one single-thread pool, a scope that exits normally and a scope whose operation throws, and after each the next
      task asserts that each key the previous scopes bound is not bound (the exceptional exit is what makes RC-5 red here: without the
      `finally` its keys stay bound). The `Context.capture()` assertions of RC-2 and RC-5 are added in T005. In both RC-5 and the RT-03 unit
      test, the throwing scope is
      caught inside the pooled task (or the task is submitted with `submit()`), never left to escape an `execute()` task, which would kill
      the worker and let the next task pass on a fresh thread; each task records `Thread.currentThread()`, and the test asserts the next task
      ran on the same `Thread` as the throwing one. Green: `Context.without`, `ContextBinding.without` and `call`, each checking its argument
      first, with changes applied in call order; `ThreadLocalSlot` restores in `finally` and calls `remove()` when the outermost scope exits
      (CORE-03, CORE-06, CORE-07, CORE-08, RT-03; RC-2, RC-5). The reviewer checks `remove()` against RT-03, since the API cannot observe it.
      The reviewer also checks that `call` checks its operation before entering the scope; this is plan design, not a CORE-03 requirement
      (with the `finally`, a `NullPointerException` from inside the scope also satisfies CORE-03 and passes the test). Verify: Unit
      `ContextBindingTest`; RC
- [x] T004 [US1] Value checks. Red: in `ContextBindingTest`, `CORE-03 binding null throws NullPointerException at the with call` and `CORE-04
      binding a value of the wrong type throws ClassCastException at the with call` (through a raw key), each for `Context.with` and
      `ContextBinding.with`, asserting the exception from `with` alone, without calling `run` or `get`; enable RC-7, which asserts both
      exceptions at the `with` call in the same way. Green: `requireNonNull` and `key.type().cast` in both `with` methods (CORE-03, CORE-04;
      RC-7). Verify: Unit `ContextBindingTest`; RC
- [x] T005 [US1] Capture and re-entry. Red: `test/ContextSnapshotTest.java` with `CORE-09 capture returns the current snapshot without copying`
      (same instance inside `snapshot.run`), `CORE-09 capture outside any scope returns EMPTY`, `CORE-10 snapshot run replaces the caller's
      snapshot and restores it`, `CORE-10 snapshot call returns the result and rethrows checked exceptions`, `CORE-11 one snapshot runs
      concurrently on many threads`, `CORE-03 snapshot run and call reject a null operation with NullPointerException and leave the current
      snapshot unchanged` (a non-empty snapshot run from `EMPTY`, then `capture()` is `EMPTY`); in `ContextBindingTest`, extend T002's
      `CORE-03 run rejects a null operation …` and T003's `CORE-03 call rejects a null operation …` with the assertion that `Context.capture()`
      is `EMPTY` afterwards, T003's `RT-03 a pooled thread carries no residue …` with the assertion that the next task's `Context.capture()`
      is `EMPTY`, and T003's `CORE-07 nested bindings restore the exact previous snapshot` with the assertion that `capture()` after the
      nested scope returns the same instance as before it (`isSameAs`) (these no longer compile without `capture`, so they fail with the rest
      of the Red step); enable RC-3 and RC-4, and in `RuntimeConformanceIT` extend RC-5 (the next task's `Context.capture()` is `EMPTY` after
      each scope) and RC-2 (`capture()` after each nested scope is the same instance as before it, `isSameAs`), keeping their display names
      and comments as T003 set them.
      Green: `Context.capture`, `ContextSnapshot.run` and `call`, each checking its operation first
      (CORE-03, CORE-09, CORE-10, CORE-11; RC-3, RC-4; RC-2 and RC-5 extended). The reviewer checks the check comes before the scope is
      entered, as in T003 (plan design, not a CORE-03 requirement). Verify: Unit `ContextSnapshotTest`, `ContextBindingTest`; RC
- [x] T006 [US1] Wrapped operations. Red: in `ContextSnapshotTest`, `CORE-10 wrap(Runnable) runs later on another thread with the snapshot` and
      `CORE-10 wrap(Callable) runs later on another thread with the snapshot and returns its result`, each restoring the executing thread's
      snapshot afterwards, and `CORE-03 wrap rejects a null operation with NullPointerException at the wrap call` (both overloads; the
      exception comes from `wrap` itself, without running the wrapper, which without the check would fail only later on the thread that runs
      it). Green: both `ContextSnapshot.wrap` methods, each checking its operation first (CORE-03, CORE-10). Verify: Unit `ContextSnapshotTest`
- [x] T007 [US1] Snapshot inspection. Red: in `ContextSnapshotTest`, `CORE-06 snapshot exposes bindings read-only` (`find` returns bound values
      only, never defaults; `keys()` rejects modification; `isEmpty`), `CORE-12 toString lists key names with each value's toString` and
      `CORE-03 snapshot find rejects a null key with NullPointerException` (on `EMPTY` and on a non-empty snapshot). Green: `find` (checking
      its key first), `keys`, `isEmpty` and `toString` in `ImmutableContextSnapshot` (CORE-03, CORE-06, CORE-12). Verify: Unit
      `ContextSnapshotTest`
- [x] T025 [US1] Snapshot `toString` order. Added after T007 was committed, for the amended CORE-12 (ascending order of key name by
      `String.compareTo`); numbered after T024 so existing IDs stay stable, and placed here because it only amends T007. Red: in
      `ContextSnapshotTest`, `CORE-12 toString lists entries in ascending order of key name`: one binding binds 16 keys with distinct names, in
      descending order of name (so insertion order is the reverse of the required order), each to a distinct `String` value (`v01` – `v16`).
      The names include `acme.a`, `acme.a-b`, `acme.a.b`, `acme.a0`, `acme.ab`, `acme.k10` and `acme.k2`, whose `String.compareTo` order
      (character by character: `-` before `.` before digits before letters) differs from a dot-segment or numeric order. The test finds each
      `name=value` entry in `toString()`, asserts that each occurs exactly once, and asserts that their positions increase in ascending
      `String.compareTo` order of name; it does not pin the wrapper or the delimiter. No two keys share a name, since CORE-12 leaves their
      order unspecified. Red is deterministic, not flaky: insertion order is excluded by construction, and T007's `Map.copyOf` iteration order
      comes from the keys' identity hash codes and a per-JVM salt, which are unrelated to the names, so the unsorted code passes only if that
      order of 16 entries happens to be the sorted one, about 1 in 16! (2·10^13) per run, which stays negligible even allowing for the salt's
      choice of start slot and direction. Green: in `ImmutableContextSnapshot.toString()`, sort the entries by `getKey().name()` (natural
      `String` order) before joining, each value still printed only through its own `toString()` (CORE-12). T007's CORE-12 test stays as it
      is. Verify: Unit `ContextSnapshotTest`
- [x] T008 [US1] Backend report and override property. Red: `test/internal/BackendOverrideTest.java` with `RT-04 thread-local forces the
      ThreadLocal backend`, `RT-04 auto and unset select automatically` (no warning), and `RT-04 an unrecognised value logs a warning and falls
      back to automatic selection` (parameterised: `THREAD-LOCAL`, `Thread-Local`, `threadlocal`, the empty string; a recording
      `System.Logger` passed in receives one `WARNING`); `test/internal/SlotFactoryTest.java` with `RT-02 the base SlotFactory creates the
      ThreadLocal slot whether or not the override forces it` (`create(true)` and `create(false)` both report `THREAD_LOCAL`);
      `test/ContextRuntimeTest.java` with `RT-05 backend reports the backend of the slot the runtime uses` (`THREAD_LOCAL` from class
      directories; the `SCOPED_VALUE` half is RC-1 in T009). Green: `core/internal/BackendOverride.java`; the base `SlotFactory` with
      `create()` (reads `boundrail.context.backend` through `BackendOverride` with a `System.Logger` named `io.github.boundrail.context`) and
      package-private `create(boolean forceThreadLocal)`; `ContextSlot.backend()`; `ContextRuntime.Backend` and `ContextRuntime.backend()`
      (RT-02, RT-04, RT-05). Verify: Unit `BackendOverrideTest`, `SlotFactoryTest`, `ContextRuntimeTest`
- [x] T009 [US1] ScopedValue backend on Java 25. Red: enable RC-1 with the JDK check: it reads `boundrail.it.task`, fails (not skips) when it is
      missing or names another task, and asserts `Runtime.version().feature()` and `ContextRuntime.backend()` per task (21 and `THREAD_LOCAL`;
      25 and `SCOPED_VALUE`; 25 and `THREAD_LOCAL`); see it fail in `integrationTestJdk25`. Green: `core25/internal/SlotFactory.java` (same
      signatures as the base; ScopedValue unless `BackendOverride` forces ThreadLocal), `core25/internal/ScopedValueSlot.java`
      (package-private), and in `build-logic/src/main/kotlin/boundrail.multi-release.gradle.kts` add `main`'s output to the `java25` compile
      classpath. RC-2 – RC-7 now run on `SCOPED_VALUE` in `integrationTestJdk25` and must pass unchanged. Prove RC-1 can fail, reverting each
      change: drop the override property from `integrationTestJdk25ThreadLocal`; register `integrationTestJdk21` with the JDK 25 launcher; drop
      `boundrail.it.task` (RT-02, RT-04, RT-05, BLD-06; RC-1; RT-06 through SC-001). No `null` check in `internal`:
      `ScopedValueSlot.call` gets none (G16). Verify: RC; `jar tf` on the core JAR lists
      `META-INF/versions/25/io/github/boundrail/context/internal/ScopedValueSlot.class`

**Checkpoint**: RC-1 – RC-7 green on all three integration test tasks; US1 is usable on its own.

---

## Phase 3: User Story 2 - Propagation policy (Priority: P2)

**Goal**: The four codecs, and a validated, immutable per-key policy that can filter and merge.

**Independent Test**: `./gradlew :boundrail-context-core:test --tests '*Codecs*' --tests '*ContextPolicy*'`

- [ ] T010 [P] [US2] String and long codecs. Red: `test/CodecsTest.java` with `WIRE-03 each Codecs method returns the same instance on every
      call` (for `string` and `longValue`), `WIRE-03 encode and decode throw NullPointerException for null`, `WIRE-03 string round-trips every
      string unchanged` (including the empty string), `WIRE-03 longValue round-trips valid values` (`0`, `-1`, `Long.MIN_VALUE`,
      `Long.MAX_VALUE`; `encode` is `Long.toString`) and `WIRE-03 longValue rejects invalid input with IllegalArgumentException`
      (parameterised: `+1`, ` 1`, `007`, `-0`, `٣`, `9223372036854775808`, the empty string). Green: `core/ContextCodec.java`,
      `core/Codecs.java` with `string()` and `longValue()` as constants (WIRE-03). Verify: Unit `CodecsTest`
- [ ] T011 [US2] UUID codec. Red: in `CodecsTest`, `WIRE-03 uuid round-trips valid values and encodes in lower case`, `WIRE-03 uuid decodes
      hexadecimal digits in either case`, `WIRE-03 uuid rejects invalid input with IllegalArgumentException` (parameterised: `1-2-3-4-5`, a
      value in braces, a `urn:uuid:` prefix, 32 digits without hyphens, a non-hexadecimal digit), and the singleton and `null` cases for
      `uuid`. Green: `Codecs.uuid()` (WIRE-03). Verify: Unit `CodecsTest`
- [ ] T012 [US2] Locale codec. Red: in `CodecsTest`, `WIRE-03 locale round-trips locales whose language tag maps back to them` (`Locale.ROOT`,
      `ja_JP_JP`, `en_GB`, `zh-Hant-TW`; `encode` equals `toLanguageTag()`, for `ja_JP_JP` on JDK 25 `ja-JP-u-ca-japanese-x-lvariant-JP`, and
      `decode` gives back the locale), `WIRE-03 locale encodes other locales without error` (`no_NO_NY`, `Locale.of("xyz123")`), `WIRE-03
      locale decodes RFC 5646 language tags ignoring case` (parameterised: `en`, `EN-gb`, `zh-Hant-TW`, `es-419`, `und`, `x-foo`, `i-klingon`,
      each equal to `Locale.forLanguageTag(text)`), `WIRE-03 locale rejects invalid input with IllegalArgumentException` (parameterised: the
      empty string, `en_GB`, `en-`, `e`, `en--GB`, ` en`), and the singleton and `null` cases for `locale`. Green: `Codecs.locale()` with a
      precompiled case-insensitive expression for the `Language-Tag` production of RFC 5646 §2.1 (WIRE-03). The reviewer checks the expression
      against the RFC's ABNF, including the 26 grandfathered tags. Verify: Unit `CodecsTest`
- [ ] T013 [US2] Policy builder and queries. Red: `test/ContextPolicyTest.java` with
      `POL-01 a built policy reports each key's codec, wire name, required, loggable and boundaries` (`codec(key)` returns the registered instance
      and `wireName(key)` the registered name), `POL-01 codec and wireName are empty when none is registered` (a registered key without them, and an
      unregistered key), `POL-01 a built policy does not change when its builder is used again`,
      `POL-02 an unregistered key propagates to ATTACHED and DETACHED only and is neither required nor loggable`,
      `POL-03 a key propagates to a remote boundary only if the policy lists it`, and
      `CORE-03 the policy builder, KeyRules and queries reject null with NullPointerException` (parameterised, each asserted at the call itself:
      `Builder.key` with a `null` key or rules consumer; `KeyRules.codec(null)`, `wireName(null)`, `propagateTo((Boundary[]) null)` and
      `propagateTo(ATTACHED, null)` asserted inside the rules consumer, the last red without the element check since `Set.copyOf` would throw only
      at `build()`; for each `KeyRules` case the consumer sets an `AtomicBoolean` after its `assertThrows`, and the test calls `key(…)` and
      `build()` before asserting the flag is `true`, so a consumer that never ran fails instead of passing vacuously; `propagates` with a `null` key
      or boundary, including `propagates(unregisteredKey, null)`; `isRequired`, `isLoggable`, `wireName` and `codec` with a `null` key). Green:
      `core/Boundary.java`, `core/ContextPolicy.java` with `builder()`, `Builder`, `KeyRules`, `propagates`, `isRequired`, `isLoggable`, `wireName`
      and `codec`, each checking its arguments first, with `propagateTo` also checking each element of its array at the call; `Builder.key` runs the
      rules consumer once, on a fresh `KeyRules`, before it returns (plan design, decided in OD-2, [plan.md](plan.md#research-notes))
      (CORE-03, POL-01, POL-02, POL-03). Verify: Unit `ContextPolicyTest`
- [ ] T014 [US2] Build validation. Red: `test/ContextPolicyValidationTest.java` with `POL-03 build fails when an application key lists a remote
      boundary without a codec`, `… without a wire name`, `POL-05 build fails on two different keys with the same name`, `POL-05 build fails on
      two different keys with wire names equal ignoring case`, `POL-05 build accepts one key registered twice with equal rules` (same
      `Codecs.string()` instance, same wire name, flags and boundaries; the built policy reports those rules), `POL-05 build fails when one key
      is registered with conflicting rules` (parameterised: another codec instance, a wire name differing only in case, a different required
      flag, loggable flag or boundary set), `POL-09 build fails on the wire name Authorization in any case`, `POL-09 build fails on a wire name
      starting with Boundrail- in any case`, each failure with `IllegalStateException`. Green: validation in `ContextPolicy.Builder.build()`
      (POL-03, POL-05, POL-09). Verify: Unit `ContextPolicyValidationTest`
- [ ] T015 [US2] Filtering. Red: in `ContextPolicyTest`, `POL-04 filter keeps exactly the bindings whose keys propagate to the boundary` (a
      registered key at a listed and an unlisted boundary; an unregistered key at `DETACHED` and at `INTERNAL_CALL`) and `CORE-03 filter
      rejects a null snapshot or boundary with NullPointerException` (`filter(null, boundary)`, and `filter(ContextSnapshot.EMPTY, null)`,
      which without the check would return `EMPTY`; a non-empty snapshot is not used for the `null` boundary, since T013's `propagates`
      already throws for it and would hide a missing check in `filter`). Green: `ContextPolicy.filter`, checking both
      arguments first (CORE-03, POL-04). Verify: Unit `ContextPolicyTest`
- [ ] T016 [US2] Merging. Red: in `ContextPolicyValidationTest`, `POL-05 merge combines the keys of both policies`, `POL-05 merge accepts the same
      key with equal rules`, `POL-05 merge fails on two different keys with the same name`, `POL-05 merge fails on two different keys with wire
      names equal ignoring case`, `POL-05 merge fails on conflicting rules for one key` (another codec instance, a wire name differing only in
      case, a different flag or boundary set), each failure with `IllegalStateException`, and `CORE-03 merge rejects null with
      NullPointerException`. Green: `ContextPolicy.merge`, checking its argument first and sharing the rule comparison of T014 (CORE-03,
      POL-05). Verify: Unit `ContextPolicyValidationTest`

**Checkpoint**: POL-01 – POL-05, POL-09 and WIRE-03 tested; POL-06 follows in Phase 4, once the built-in keys exist.

---

## Phase 4: User Story 3 - Built-in deadline, authority and initiator values (Priority: P3)

**Goal**: The three built-in values, their keys and fixed policies, and deadlines that only shrink.

**Independent Test**: RC-8 in the RC command above, plus `./gradlew :boundrail-context-core:test --tests '*Deadline*' --tests '*BuiltInKeys*'
--tests '*Authority*' --tests '*Initiator*'`

- [ ] T017 [P] [US3] Deadline value. Red: `test/DeadlineTest.java` with `DL-01 remaining is never negative` (including a negative budget),
      `DL-01 a zero or negative duration gives an already expired deadline` (through `after` and through `within`),
      `DL-01 a long budget is not expired`, `DL-01 check throws DeadlineExceededException with stage APPLICATION when expired`,
      `DL-01 check passes when not expired`, `DL-01 earliest returns the deadline that expires first` (either order; no tie case, since DL-01 allows
      either), `DL-01 within returns this when max ends later`, `DL-01 within returns a deadline at most max away when max ends sooner`,
      `DL-01 after caps a budget at or above the maximum budget` (parameterised: `Duration.ofNanos(Long.MAX_VALUE / 2)`,
      `Duration.ofNanos(Long.MAX_VALUE)`, `ChronoUnit.FOREVER.getDuration()`; no exception, and `remaining()` is at most
      `Duration.ofNanos(Long.MAX_VALUE / 2)` and at least that cap minus one minute),
      `DL-01 within a budget at or above the maximum keeps the instant of this` (with `ChronoUnit.FOREVER.getDuration()`: for `d = after(1 hour)`,
      `d.within(FOREVER)` is `d` itself, since `d` is strictly earlier; for `d = after(FOREVER)`, no exception, and its `remaining()`, read after
      `d.remaining()`, is at most that value; this sub-case's value is "no exception near the cap", while choosing the wrong deadline is caught by
      the 1-hour identity case and the `earliest` test), `DL-01 a negative budget whose nanoseconds overflow gives an expired deadline`
      (`ChronoUnit.FOREVER.getDuration().negated()` through `after` and through `within`: no exception, expired, `remaining()` zero), and
      `CORE-03 after, earliest and within reject null with NullPointerException`. Green: `core/Deadline.java` (clamping the budget as a `Duration`
      before `toNanos()`, as [plan.md](plan.md#research-notes) describes), `core/DeadlineExceededException.java` with `Stage` (DL-01, CORE-03).
      Review note: without clock injection no test can choose the `System.nanoTime()` origin, so the reviewer checks that the arithmetic is
      overflow-safe for any origin (DL-01's second paragraph): instants compared only through their difference, `now + nanos` allowed to wrap, and
      no `toNanos()` before the clamp. Verify: Unit `DeadlineTest`
- [ ] T018 [US3] Built-in keys. Red: `test/BuiltInKeysTest.java` with `CORE-01 built-in keys use their reserved names` (`boundrail.deadline`,
      `boundrail.authority`, `boundrail.initiator`, with types `Deadline`, `Authority`, `Initiator`) and `DL-02 Deadlines.current is equivalent to
      DEADLINE.find` (bound and unbound). Green: `core/BuiltInKeys.java` (the three constants, through a package-private `ContextKey` factory
      that skips only the reserved-prefix check), `core/Deadlines.java`, and bare `core/Authority.java` and `core/Initiator.java` class
      declarations for the key types (their factories and behaviour come in T020 and T021) (CORE-01, DL-02). Verify: Unit `BuiltInKeysTest`
- [ ] T019 [US3] Deadlines only shrink. Red: `test/DeadlineScopeTest.java` with `DL-10 binding a later deadline keeps the current one`,
      `DL-10 binding an earlier deadline shortens it`, `DL-10 binding a deadline with none current binds it`, `DL-11 Context.without(DEADLINE)
      throws IllegalArgumentException`, `DL-11 ContextBinding.without(DEADLINE) throws IllegalArgumentException`, both with and without a
      deadline current; enable RC-8. Green: in `ContextBinding`, bind `current.earliest(bound)` when running a binding of `DEADLINE`, and reject
      `without(DEADLINE)` at the call in `Context` and `ContextBinding` (DL-10, DL-11; RC-8). Verify: Unit `DeadlineScopeTest`; RC
- [ ] T020 [P] [US3] Authority. Red: `test/AuthorityTest.java` with `ID-01 toString never contains the credential`, `ID-01 a snapshot's
      toString never contains the credential` (CORE-12 path), `ID-01 equals and hashCode are identity-based` (two authorities with the same
      subject and credential are not equal), `CORE-03 Authority.of rejects a null subject or credential with NullPointerException`, and
      `subject()`/`credential()` return what was given. Green: `Authority.of`, accessors, the redacted `toString` of the contract, no
      `equals`/`hashCode` override (the inherited `Object.equals` is not covered by CORE-03, G13) (ID-01, CORE-03). Verify: Unit
      `AuthorityTest`
- [ ] T021 [P] [US3] Initiator. Red: `test/InitiatorTest.java` with `ID-07 accepts values of 1 and 256 characters`,
      `ID-07 counts length in UTF-16 chars` (128 supplementary characters, 256 chars, are accepted; 129, 258 chars, are rejected),
      `ID-07 rejects an empty value with IllegalArgumentException`,
      `ID-07 rejects a value longer than 256 characters with IllegalArgumentException`, `ID-07 equals and hashCode compare values`,
      `ID-07 toString returns the value unchanged`, `CORE-03 Initiator.of rejects null with NullPointerException`,
      `CORE-03 Initiator.equals(null) is false`, and `CORE-12 a snapshot's toString shows an initiator's value`. Green: `Initiator.of`, `value()`,
      `equals`, `hashCode` and `toString` (ID-07, CORE-03, CORE-12). Verify: Unit `InitiatorTest`
- [ ] T022 [US3] Built-in default policy. Red: in `BuiltInKeysTest`, `DL-02 DEADLINE uses the built-in default policy`, `POL-06 AUTHORITY uses
      the built-in default policy`, `POL-06 INITIATOR uses the built-in default policy` (each row of the spec's table: boundaries, required,
      loggable; wire name `Boundrail-Deadline-Ms` for `DEADLINE`, `Boundrail-Initiator` for `INITIATOR`, empty for `AUTHORITY`),
      `POL-06 defaultPolicy holds only the built-in rules` (an application key is unregistered in it), and `POL-03 the built-in rules have no
      codec and build although they list remote boundaries` (`codec(key)` empty for all three; the `Boundrail-` wire names are allowed there).
      Green: `BuiltInKeys.defaultPolicy()` through a package-private path of `ContextPolicy` that skips POL-03's check, POL-06's rejection and
      POL-09 (DL-02, POL-03, POL-06). Verify: Unit `BuiltInKeysTest`
- [ ] T023 [US3] Built-in rules in every policy. Red: in `ContextPolicyValidationTest`, `POL-06 build fails when a builder registers a built-in
      key` (each of the three, with `IllegalStateException`), `POL-06 every built policy includes the built-in rules` (in `builder().build()`,
      `DEADLINE` does not propagate to `DETACHED`, `INITIATOR` reports `Boundrail-Initiator`, and `filter` drops `DEADLINE` at `DETACHED`),
      `POL-05 merging two built policies accepts their equal built-in rules` (the merge succeeds and the merged policy reports a built-in
      rule: `wireName(DEADLINE)` is `Boundrail-Deadline-Ms`, which is red until built policies include the built-in rules). Green: `build()`
      rejects built-in keys and adds the default policy's rules (POL-05, POL-06). Verify: Unit `ContextPolicyValidationTest`

**Checkpoint**: RC-1 – RC-8 green on all three integration test tasks; every M1 requirement has its test.

---

## Phase 5: Close

- [ ] T024 Verify each requirement one by one against the code and its tests: CORE-01 – CORE-12, RT-01 – RT-06, POL-01 – POL-06, POL-09,
      WIRE-03, DL-01, DL-02, DL-10, DL-11, ID-01, ID-07, and RC-1 – RC-8 (SC-001), using the coverage table in
      [plan.md](plan.md#requirement-coverage). Confirm SC-002 by listing test display names per ID (RT-06 is exempt and verified by SC-001), and
      SC-003 with `./gradlew :boundrail-context-core:dependencies --configuration runtimeClasspath` (no dependencies). Confirm spec.md and the
      M1 rows of `docs/roadmap.md` no longer list RT-09 (it is in 004). Confirm the decided CORE-03 scope (G13 – G16): `Initiator.equals(null)`
      is `false`, `propagateTo(ATTACHED, null)` throws at the call, and `internal` has no `null` checks. Confirm the CORE-03 precedence:
      `ContextKey.of` checks every argument for `null` before the CORE-01 checks, and T001's `CORE-03 a null argument takes precedence over
      CORE-01 checks` passes. Confirm RuntimeConformanceIT
      leaves only RC-9 disabled (`Pending: M3`). Run `./gradlew check` fresh, which covers JDK 21, JDK 25 and the forced ThreadLocal run;
      with the CI run green, this closes the feature (leader step 5)

## Dependencies & Execution Order

- US1 in order T001 → T007 → T025 → T008 → T009. T025 needs only T007 (it changes `ImmutableContextSnapshot.toString()` and extends
  `ContextSnapshotTest`); it is listed after T007 but may run at any point before T024. T009 needs T008 (`BackendOverride`,
  `SlotFactory.create(boolean)`, `backend()`) and runs every RC scenario enabled so far on `SCOPED_VALUE`.
- US2: T010 can run any time after T001; T010 → T011 → T012 (they share `Codecs.java` and `CodecsTest.java`). T013 needs T001 and T010
  (`ContextCodec`). T013 → T014 → T015 → T016. T015 also needs T007 (it builds and reads snapshots through `keys()` and `find`). T012 is
  independent of T013 – T016.
- US3: T017 needs nothing from US2 and can start after T001. T018 needs T002 and T017. T019 needs T003, T017 and T018. T020 and T021 need
  T018 and T007. T022 needs T013, T014 and T018. T023 needs T015, T016 and T022.
- T024 comes last.
- G13 – G16 are decided in the spec, and OD-1 and OD-2 are decided ([plan.md](plan.md#decisions)), so no decision blocks a task.
  T001's precedence test follows OD-1; no test depends on when `Builder.key` runs its consumer (OD-2, plan design).
- Every task is committed and reviewed on its own, on branch `002-core-runtime`.

## Exit Checklist

- [ ] Scenarios enabled and green on all three JDK tasks: RC-1, RC-2, RC-3, RC-4, RC-5, RC-6, RC-7, RC-8
- [ ] Requirements implemented with tests named after their IDs: CORE-01 – CORE-12, RT-01 – RT-05, POL-01 – POL-06, POL-09, WIRE-03, DL-01,
      DL-02, DL-10, DL-11, ID-01, ID-07
- [ ] RT-06 verified by SC-001 (exempt from SC-002); RT-09 not listed in 002
- [ ] CORE-03 scope as decided: `equals(null)` is `false` for `Initiator` (G13), a `null` varargs element throws at the call (G14), and
      no `null` checks in `internal` (G16)
- [ ] CORE-03 precedence as decided in OD-1: a `null` argument to `ContextKey.of` throws `NullPointerException` before any CORE-01 check
      (T001)
- [ ] CORE-12 order as amended: `ContextSnapshot.toString()` lists entries in ascending order of key name by `String.compareTo` (T025)
- [ ] `boundrail-context-core` still has no runtime dependencies (SC-003)
- [ ] `./gradlew check` green
