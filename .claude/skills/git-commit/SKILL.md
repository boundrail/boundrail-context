---
name: git-commit
description: Write Conventional Commits messages for this repo and create the commit. Use when the user asks to commit ("commit", "commit this", "create a commit"), or when the leader agent commits a reviewed task on an approved feature branch. Do not commit on your own initiative otherwise.
license: MIT
---

# Git Commit Message Skill

Write conventional, informative commit messages for Boundrail Context.

## When to Use
- The user says "commit" / "commit this" / "create a commit"
- The `leader` agent commits a task after its review passes, on the feature branch (e.g. `001-foundation`) of a feature whose
  spec, plan and tasks the user approved. That approval is the request. Reference the task and requirement IDs in the body.
- Never commit unprompted otherwise, even after finishing a change — offer instead. Never commit to `main` or push without the user.

## Branch names

Never commit on `main`; create a branch first.

- **Feature work** (a milestone from `specs/`): the feature folder name, e.g. `001-foundation`, `004-async-diagnostics`.
- **Everything else**: `<type>/<topic>`.
  - `<type>` is a commit type from the list below (`fix`, `docs`, `build`, `ci`, `chore`, …), or `setup` for repository, tooling or
    workflow setup. Pick the type that describes most of the branch's work.
  - `<topic>` is short kebab-case: lowercase words joined by hyphens, no IDs or dates, e.g. `docs/deadline-examples`,
    `build/gradle-9-upgrade`, `setup/spec-driven-workflow`.

## Format

Conventional Commits:
```
<type>(<scope>): <subject>

<body>

<footer>
```

### Types
- **feat**: new behaviour or public API
- **fix**: bug fix
- **refactor**: no behaviour change
- **test**: add or update tests
- **docs**: documentation only (`docs/`, README, Javadoc)
- **perf**: performance improvement
- **build**: Gradle, `build-logic`, `libs.versions.toml`, dependencies
- **ci**: GitHub Actions workflows
- **chore**: other maintenance

### Scopes
Use the module or area the change is in:

| Scope | Covers |
|-------|--------|
| `core` | `boundrail-context-core` |
| `spring` | `boundrail-context-spring` |
| `kafka` | `boundrail-context-kafka` |
| `test` | `boundrail-context-test` |
| `it` | `integration-tests` |
| `examples` | `examples/*` |
| `deps` | dependency version bumps (with `build`) |

Omit the scope when a change spans most modules.

### Subject
- Imperative mood: "add", not "added"
- Lowercase after the colon, no trailing period
- At most 50 characters

### Body (optional)
- Explain what and why, not how
- Wrap at 72 characters
- Reference the spec requirement or milestone when there is one: `Implements RT-02.`,
  `Enables M1 scenarios.`
- Reference issues: `Fixes #123`, `Refs #456`

### Breaking changes
Add `!` after the type/scope and a `BREAKING CHANGE:` footer explaining the migration.

## Examples

```
feat(core): select ScopedValue backend on Java 25

Add the java25 variant of the backend selector so the multi-release
JAR picks ScopedValue on 25+ and ThreadLocal on 21-24.

Implements RT-02.
```

```
fix(spring): restore previous MDC values on scope exit

The filter cleared the whole MDC in finally, wiping values set by the
application before the request entered the scope.

Implements LOG-01.
```

```
build(deps): bump Spring Boot from 4.0.1 to 4.0.2
```

```
feat(core)!: make ContextKey.get throw when unbound without default

BREAKING CHANGE: get() no longer returns null for an unbound key
with no default. Use find() where absence is expected.
```

## Workflow

1. Read the change: `git diff --staged --stat`, then targeted file diffs
2. Pick the type and scope from what changed
3. Write the message in the format above
4. Commit with a heredoc so the body keeps its line breaks:
   `git commit -F - <<'EOF' ... EOF`

## Anti-patterns
- "fix stuff" / "update code" / "WIP" (unless asked)
- Mixing unrelated changes in one commit — split them
- Describing the implementation line by line in the body

## References
- [Conventional Commits](https://www.conventionalcommits.org/)
