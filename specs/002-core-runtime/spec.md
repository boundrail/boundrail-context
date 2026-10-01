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

1. **RC-1 Active backend matches the JDK and the override.** The active backend is `SCOPED_VALUE` under `integrationTestJdk25`, and `THREAD_LOCAL`
   under `integrationTestJdk21` and `integrationTestJdk25ThreadLocal`. The runtime feature version (`Runtime.version().feature()`) is 21 under
   `integrationTestJdk21` and 25 under the other two tasks, so the override is proven on JDK 25. The task is read from the system property
   `boundrail.it.task` (BLD-06); RC-1 fails, and is not skipped, when that property is missing or names a task other than these three.
   *(RT-02, RT-04, RT-05, BLD-06)*
2. **RC-2 Nested bindings restore the exact previous snapshot.** Nested bindings rebind and remove keys; each scope exit restores the exact
   previous snapshot, including when the operation throws a runtime exception or a checked exception. *(CORE-07, CORE-08)*
3. **RC-3 Snapshot run replaces the caller's snapshot.** `snapshot.run` replaces the caller's snapshot rather than merging with it, and restores
   it afterwards. *(CORE-10)*
4. **RC-4 One snapshot is shared safely across virtual threads.** One snapshot run concurrently on 1,000 virtual threads makes the same values
   visible on every thread, and bindings made inside those threads never become visible to the others. *(CORE-11)*
5. **RC-5 Pooled threads carry no residue.** After a scope exits on a pooled platform thread, the next task on that thread observes `EMPTY`.
   *(RT-03)*
6. **RC-6 Missing keys and defaults.** `get()` on an unbound key without a default throws `MissingContextException` naming the key; with a
   default, it returns the default and `isBound()` is false. *(CORE-05)*
7. **RC-7 Null and wrong-type values are rejected when binding.** Binding `null` throws `NullPointerException`, and binding a value of the wrong
   type throws `ClassCastException` at the binding call. *(CORE-03, CORE-04)*

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

1. **RC-8 Deadlines can be shortened but never extended or removed.** Within a scope whose deadline has 100ms remaining, binding a deadline of 10s
   leaves at most 100ms remaining, binding one of 10ms shortens it to at most 10ms, and `Context.without(DEADLINE)` throws
   `IllegalArgumentException`. *(DL-10, DL-11)*

---

### Edge Cases

- Invalid or reserved key names, and primitive key types: CORE-01. Null values and defaults, and a `null` argument or varargs element (such as
  `propagateTo(ATTACHED, null)`) to a method or factory the contract declares, including operations passed to `run`, `call` or `wrap`: CORE-03 (that
  call throws, not a later one). A `null` argument together with another invalid argument, such as a reserved name: CORE-03 (`NullPointerException`,
  not CORE-01's `IllegalArgumentException`). `equals(null)`: CORE-03 (returns `false`). Methods inherited from JDK types, application-written
  implementations of the library's interfaces, and the `internal` package: not covered by CORE-03. Wrong value types: CORE-04.
- One binding that changes the same key twice: CORE-07 (the last change wins).
- Application-written `ContextSnapshot` implementations: not covered by CORE-12.
- Conflicting keys, wire names or rule sets across merged policies: POL-05. The same key registered twice with equal rules: POL-05 (accepted).
  Reserved wire names: POL-09. Built-in keys re-registered: POL-06. Merging policies that both contain the built-in rules: POL-06 (accepted).
- An unrecognised `boundrail.context.backend` value, including a different case such as `THREAD-LOCAL`: RT-04.
- A zero or negative deadline budget: DL-01 (already expired). Two deadlines that expire at the same instant: DL-01 (`earliest` may return either).
- A budget at or above the maximum budget, including one whose `toNanos()` overflows such as `ChronoUnit.FOREVER.getDuration()`: DL-01 (no
  exception; `after` caps it at about 146 years, and `within` keeps the instant of `this`). A negative budget whose `toNanos()` overflows: DL-01
  (no exception; already expired).

## Requirements *(mandatory)*

### Core API

**CORE-01** `[M1]` Key names SHALL match `[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)*`. The prefix `boundrail.` is reserved for built-in keys. IF a key is
created with an invalid or reserved name, or with a primitive type such as `long.class` (`Class.isPrimitive()`), THEN `ContextKey.of` SHALL throw
`IllegalArgumentException`.

**CORE-02** `[M1]` Key equality SHALL be by instance. Two keys with the same name are different keys; conflicts between them are rejected by the
policy (POL-05).

**CORE-03** `[M1]` Context values and key defaults SHALL be non-null. IF `null` is passed as an argument, or as an element of a varargs argument, to
a method or factory declared in this feature's contract (`contracts/core-api.md`), THEN that call SHALL throw `NullPointerException`. The `null`
check SHALL take precedence over the call's other argument checks, so a call with a `null` argument and another invalid argument (such as
`ContextKey.of("boundrail.x", String.class, null)`) SHALL throw `NullPointerException`. Exception: `equals(Object)` SHALL return `false` for `null`.
This binds the library's own classes and its own implementations of its interfaces only; it does not cover methods inherited from JDK types (such as
`Throwable.initCause` or `Enum.equals`), implementations written by applications (such as of `ContextSnapshot`, `ContextCodec`,
`ContextPolicy.Builder` or `KeyRules`), or public classes in the `internal` package. Values SHOULD be immutable; the library does not copy them.

**CORE-04** `[M1]` IF a value is not an instance of the key's type, THEN binding it SHALL throw `ClassCastException` at the binding call (not at read
time).

**CORE-05** `[M1]` `key.find()` SHALL return the value bound in the current snapshot, else the key's default, else empty. `key.get()` SHALL return the
same value, or throw `MissingContextException` identifying the key when `find()` would be empty. `key.isBound()` SHALL be true only if the key is
bound in the current snapshot.

**CORE-06** `[M1]` The public API SHALL NOT offer any operation that mutates the current snapshot in place.

**CORE-07** `[M1]` WHEN a `ContextBinding` is run, the library SHALL execute the operation with a current snapshot equal to the previous current
snapshot with the binding's additions, replacements and removals applied. It SHALL restore the previous snapshot when the operation completes, whether
normally or exceptionally. When a binding changes the same key more than once, the last change for that key SHALL win, so
`Context.with(k, v).without(k)` removes `k`. Bindings of `DEADLINE` are subject to DL-10 and DL-11.

**CORE-08** `[M1]` `call` SHALL return the operation's result and rethrow its exceptions unchanged, including checked exceptions of type `X`.

**CORE-09** `[M1]` `Context.capture()` SHALL return the current snapshot, or `ContextSnapshot.EMPTY` if none is current, without copying. Its cost
SHALL be independent of the number of bound keys.

**CORE-10** `[M1]` WHEN `snapshot.run(op)` or `snapshot.call(op)` is invoked, the library SHALL execute `op` with exactly that snapshot as current,
replacing (not merging with) the caller's snapshot, and SHALL restore the caller's snapshot afterwards. `wrap` SHALL return an operation that behaves
like `run`/`call` when executed later, on any thread.

**CORE-11** `[M1]` Snapshots SHALL be immutable and safe to share across threads, including being run concurrently on many threads.

**CORE-12** `[M1]` `ContextSnapshot.toString()` SHALL list key names with each value's `toString()`, in ascending order of key name as compared
by `String.compareTo`. The relative order of two different keys with the same name (CORE-02) is unspecified. Each value SHALL appear only as the
result of its own `toString()`. (Built-in values redact themselves; see ID-01.) This binds the library's own `ContextSnapshot` implementations
only, not implementations written by applications.

### Runtime backends

**RT-01** `[M1]` The runtime SHALL hold the current snapshot in a single slot per thread of execution.

**RT-02** `[M1]` On Java 21–24 the runtime SHALL use a ThreadLocal backend. On Java 25+ it SHALL use a `ScopedValue` backend. Selection SHALL happen
through a class that exists in both the base JAR and `META-INF/versions/25/`, without reflection on Java version strings.

**RT-03** `[M1]` The ThreadLocal backend SHALL restore the previous snapshot in a `finally` block, and SHALL call `ThreadLocal.remove()` when the
outermost scope on a thread exits, so that pooled threads carry no residue.

**RT-04** `[M1]` WHERE the system property `boundrail.context.backend=thread-local` is set at startup, the runtime SHALL use the ThreadLocal backend
on any Java version. Any other value except `auto` SHALL cause a warning, and the runtime SHALL use automatic selection. Values SHALL be compared
exactly, including case, so `THREAD-LOCAL` is an unrecognised value. "At startup" means when the runtime selects its backend, on its first use.

**RT-05** `[M1]` `ContextRuntime.backend()` SHALL report the active backend.

**RT-06** `[M1]` Both backends SHALL pass RuntimeConformanceIT (the RC scenarios in this spec and RC-9 in
[004-async-diagnostics](../004-async-diagnostics/spec.md)) unchanged.

### Policy

Built-in default policies:

| Key | ATTACHED | DETACHED | INTERNAL_CALL | EXTERNAL_CALL | MESSAGE | Required | Loggable | Wire name | Codec |
|---|---|---|---|---|---|---|---|---|---|
| `boundrail.deadline` | ✓ | ✗ | ✓ | ✗ (applied, DL-07) | ✗ | no | no | `Boundrail-Deadline-Ms` | none |
| `boundrail.authority` | ✓ | ✗ | ✓ (via propagator) | never | never | no | never | none (the `AuthorityPropagator` writes `Authorization`, ID-03) | none |
| `boundrail.initiator` | ✓ | ✓ | ✓ | ✗ | ✓ | no | yes | `Boundrail-Initiator` | none |
| unregistered keys | ✓ | ✓ | ✗ | ✗ | ✗ | no | no | none | none |

**POL-01** `[M1]` `ContextPolicy` SHALL be built with a builder that registers, per key, a codec, a wire name, a required flag, a loggable flag and
the set of boundaries the key propagates to. Built policies SHALL be immutable. `codec(key)` and `wireName(key)` SHALL return the codec and wire
name registered for the key, and SHALL return empty when none is registered. A repeated call to `codec`, `wireName` or `propagateTo` on one
`KeyRules` SHALL replace the earlier value, so the last call wins. A boundary listed more than once in one `propagateTo` call SHALL count once.

**POL-02** `[M1]` A key not registered in a policy SHALL propagate to `ATTACHED` and `DETACHED` only, and SHALL be neither required nor loggable.

**POL-03** `[M1]` A key SHALL propagate to `INTERNAL_CALL`, `EXTERNAL_CALL` or `MESSAGE` only if the policy lists that boundary for it. A
registered key SHALL propagate only to the boundaries its rules list, `ATTACHED` and `DETACHED` included: a key registered with
`propagateTo(INTERNAL_CALL)` alone does not propagate to `ATTACHED` or `DETACHED`. The `ATTACHED` and `DETACHED` defaults of POL-02 apply only to
unregistered keys. IF a policy lists a remote boundary for an application key without both a codec and a wire name, THEN `build()` SHALL fail
with `IllegalStateException`.
This check applies to application keys only. The built-in rules SHALL have no codec. `DEADLINE` and `INITIATOR` SHALL have the wire names in the
table above, which are the header names of WIRE-02, and the adapters apply the WIRE-02 formats to them. `AUTHORITY` SHALL have no wire name; it is
carried by the `AuthorityPropagator` (ID-03).

**POL-04** `[M1]` `filter(snapshot, boundary)` SHALL return a snapshot containing exactly the bindings whose keys propagate to `boundary`.

**POL-05** `[M1]` IF a policy, or the result of `merge`, contains two different keys with the same name, two different keys with the same wire name
(compared case-insensitively), or two conflicting rule sets for the same key, THEN `build()` or `merge` SHALL fail with `IllegalStateException`. Two
rule sets for one key conflict unless they have the same codec instance (or both none), the same wire name compared exactly (or both none), the same
required and loggable flags, and the same set of boundaries. A key registered more than once with equal rule sets, in one builder or across merged
policies, SHALL be accepted and registered once.

**POL-06** `[M1]` Every `ContextPolicy` returned by `ContextPolicy.builder().build()` SHALL include the built-in rules (table above), and
`BuiltInKeys.defaultPolicy()` SHALL return a policy with only the built-in rules. Built-in rules are fixed in v0.1: IF a builder registers a
built-in key, THEN `build()` SHALL fail with `IllegalStateException`. The built-in rules of any two policies are equal, so merging them is not a
conflict (POL-05).

**POL-09** `[M1]` Wire names are reserved for the library if they equal `Authorization` or start with `Boundrail-`, compared case-insensitively. IF an
application policy uses a reserved wire name, THEN `build()` SHALL fail with `IllegalStateException`.

**WIRE-03** `[M1]` The codecs returned by `Codecs` (string, long, UUID and Locale) SHALL round-trip every valid value (`decode(encode(v))` equals
`v`), and `decode` SHALL reject invalid input with `IllegalArgumentException`. `encode` SHALL accept every non-null value. `encode` and `decode`
SHALL throw `NullPointerException` for `null`. Each `Codecs` method SHALL return the same instance on every call. Per codec:

- `string()`: every string is valid, including the empty string. `encode` and `decode` return their argument unchanged.
- `longValue()`: `encode` is `Long.toString`. `decode` SHALL accept exactly the strings that match `0|-?[1-9][0-9]*` in ASCII digits and are in the
  range of `long`. For example, `+1`, ` 1`, `007`, `-0`, `٣` and `9223372036854775808` are invalid.
- `uuid()`: `encode` is `UUID.toString()`, in lower case. `decode` SHALL accept exactly 36-character strings of hexadecimal digits, in either case,
  grouped 8-4-4-4-12 by hyphens. For example, `1-2-3-4-5`, a value in braces, a `urn:uuid:` prefix and 32 digits without hyphens are invalid.
- `locale()`: `encode` is `Locale.toLanguageTag()`. The valid values are the locales `l` for which `Locale.forLanguageTag(l.toLanguageTag())`
  equals `l`, for example `Locale.ROOT` and `ja_JP_JP` but not `no_NO_NY` or `Locale.of("xyz123")`; other locales are encoded without error but
  do not round-trip. `decode` SHALL accept exactly the strings that match the `Language-Tag` production of RFC 5646 §2.1, ignoring case (so
  including private-use and grandfathered tags such as `x-foo` and `i-klingon`), and SHALL return `Locale.forLanguageTag(text)`. For example,
  `en`, `EN-gb`, `zh-Hant-TW`, `es-419` and `und` are valid; the empty string, `en_GB`, `en-`, `e`, `en--GB` and ` en` are invalid. `decode`
  checks only well-formedness, not validity (RFC 5646 §2.2.9), and returns whatever `Locale.forLanguageTag` returns, even when that drops or
  rewrites subtags: `en-a-bb-a-cc` (a duplicate singleton) decodes to `en-a-bb`, and `zh-abc-def-ghi` to `abc`.

### Built-in values

**DL-01** `[M1]` `Deadline` SHALL be an immutable value based on a monotonic clock (`System.nanoTime`). `remaining()` SHALL never be negative. A
deadline is expired when its remaining time is zero, so `Deadline.after(Duration.ZERO)` is expired immediately. A zero or negative duration passed
to `Deadline.after` or `within` SHALL give a deadline that is already expired. `check()` SHALL throw `DeadlineExceededException` with stage
`APPLICATION` when the deadline has expired. `earliest(other)` SHALL return whichever of `this` and `other` expires first; when both expire at the
same instant, it MAY return either. `within(max)` SHALL be equivalent to `earliest(Deadline.after(max))` evaluated when it is called.

The maximum budget is `Duration.ofNanos(Long.MAX_VALUE / 2)`: 4,611,686,018,427,387,903 nanoseconds, about 146 years. `remaining()` SHALL never
exceed it. `Deadline.after(d)` with a `d` at or above the maximum budget, including a `d` whose `toNanos()` overflows `long` such as
`ChronoUnit.FOREVER.getDuration()`, SHALL NOT throw and SHALL give a deadline whose remaining time when created is the maximum budget, as if `d`
were the maximum budget. Because no deadline has more remaining time than the maximum budget, `within(d)` with such a `d` SHALL return a deadline
that expires at the same instant as `this`. A negative `d` whose `toNanos()` overflows SHALL NOT throw either, and gives an expired deadline. The
maximum budget is half of `Long.MAX_VALUE` so that the difference between any two deadline instants or `System.nanoTime()` readings fits in a
`long`; `remaining()`, `isExpired()` and `earliest` SHALL be correct for any `System.nanoTime()` origin, including when adding a budget to
`System.nanoTime()` overflows `long`.

**DL-02** `[M1]` `BuiltInKeys.DEADLINE` SHALL use the built-in default policy (table above), and `Deadlines.current()` SHALL be equivalent to
`BuiltInKeys.DEADLINE.find()`.

**DL-10** `[M1]` WHEN a binding binds `DEADLINE` while a deadline is current, the resulting snapshot SHALL contain the earlier of the current and the
new deadline. A child operation can shorten a deadline but never extend it. When a binding changes `DEADLINE` more than once, only its last
change SHALL apply (CORE-07), combined as above with the deadline of the snapshot current when the binding runs, so
`Context.with(DEADLINE, a).with(DEADLINE, b)` binds the earlier of the current deadline and `b`, or `b` when none is current, and `a` has no effect.

**DL-11** `[M1]` IF `Context.without` or `ContextBinding.without` is called with `DEADLINE`, THEN that call SHALL throw `IllegalArgumentException`,
whether or not a deadline is current. Work that must outlive the request's deadline SHALL use a detached boundary (EXE-02) or explicit snapshot
re-entry (CORE-10), both of which replace the context rather than extend it.

**ID-01** `[M1]` `Authority.toString()`, and any string representation the library produces of an authority, SHALL NOT contain the credential.
`equals` and `hashCode` SHALL be identity-based, so the library never compares or hashes credentials.

**ID-07** `[M1]` `Initiator` values SHALL be non-empty and at most 256 characters long, counted in UTF-16 chars (`String.length()`). IF
`Initiator.of` is given an empty value or one longer than 256 characters, THEN it SHALL throw `IllegalArgumentException`. Conventional prefixes
are `user:`, `service:` and `system:`. `equals` and `hashCode` SHALL compare values, and `toString()` SHALL return the value unchanged.

### Configuration

Properties use the prefix `boundrail.context.`.

| Property | Default | Meaning |
|---|---|---|
| `backend` (JVM system property) | `auto` | `auto` or `thread-local`, compared exactly; read when the runtime is first used (RT-04) |

### Key Entities

- **ContextKey**: name, type and optional default; identity by instance.
- **ContextSnapshot**: immutable set of key/value bindings; exactly one is current at any point of execution.
- **ContextBinding**: an immutable, pending set of additions, replacements and removals applied for one scope.
- **ContextPolicy**: per-key rules (codec, wire name, required, loggable, boundaries); immutable once built.
- **Deadline, Authority, Initiator**: the built-in value types.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: RC-1 – RC-8 are enabled and green in `integrationTestJdk21`, `integrationTestJdk25` and `integrationTestJdk25ThreadLocal`.
- **SC-002**: Every requirement in this spec except RT-06 has a unit or conformance test whose display name starts with its ID. RT-06 is verified
  by SC-001 here and by RC-9 in 004-async-diagnostics. For a `@ParameterizedTest`, the display name that counts is the method's `@DisplayName`;
  invocation names such as `[1] "THREAD-LOCAL"` need not start with the ID.
- **SC-003**: `boundrail-context-core` still has no runtime dependencies (BLD-03).

## Assumptions

- 001-foundation is complete: the multi-release build and the three integration test tasks exist.
- Boundary behaviour of the built-in keys (HTTP, executors, Kafka) is out of scope here and belongs to features 003–007.
