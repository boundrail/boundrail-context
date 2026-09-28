---
name: changelog-generator
description: Generate changelogs from git commits. Use when user says "generate changelog", "update changelog", "what changed since last release", or before preparing a new release.
license: MIT
---

# Changelog Generator Skill

Generate changelogs from conventional commits (see the `git-commit` skill for this repo's types and scopes).

## When to Use
- Before a release
- User says "generate changelog" / "update changelog" / "what changed since last release"
- After completing a milestone

## Versioning Convention Detection

Detect versioning style using this priority order:

### 1. Check AGENTS.md (if exists)
```bash
grep -A5 "## Versioning" AGENTS.md 2>/dev/null
```

Look for explicit convention:
```markdown
## Versioning
This project uses Semantic Versioning (x.y.z).
Tag format: `release-x.y.z`
```

### 2. Fallback: Detect from git tags
```bash
git tag --sort=-version:refname | head -10
```

| Pattern detected | Versioning style |
|------------------|------------------|
| `v3.15.0`, `3.15.0` | SemVer (x.y.z) |
| `release-3.15.0` | SemVer with prefix |
| `v2.1`, `2.1` | Two-component (x.y) |
| `2026.01`, `26.1` | CalVer |
| No pattern | Ask user |

### 3. Fallback: Detect from CHANGELOG.md
```bash
grep -E "^\#+ \[.*\]" CHANGELOG.md | head -5
```

Extract version format from existing entries.

### 4. Last resort: Ask user
```
No versioning convention detected. Which format does this project use?
- Semantic Versioning (x.y.z) - e.g., 3.15.0
- Two-component (x.y) - e.g., 2.1
- Calendar Versioning - e.g., 2026.01
```

### Supported Versioning Styles

| Style | Format | Tag examples | Version bump |
|-------|--------|--------------|--------------|
| SemVer | x.y.z | `v3.15.0`, `release-3.15.0` | major.minor.patch |
| Two-component | x.y | `v2.1`, `2.1` | major.minor |
| CalVer | YYYY.MM[.patch] | `2026.01`, `2026.01.1` | year.month[.patch] |

### Legacy Projects (AGENTS.md without versioning section)

If AGENTS.md exists but has no versioning info:
1. Don't assume - detect from tags/changelog
2. If detected, optionally suggest adding to AGENTS.md:
   ```
   Detected versioning: SemVer (x.y.z) with tag prefix 'release-'
   Want me to add this to AGENTS.md for future reference?
   ```

## Output Format

Supports two formats - detect from existing CHANGELOG.md or ask user preference.

### Format A: Keep a Changelog (h2 versions)
```markdown
# Changelog

## [Unreleased]

## [1.2.0] - 2026-01-29

### Added
- [#123]: Bind tenant from a verified JWT claim

### Changed
- [#456]: Cap outbound HTTP timeouts at the remaining deadline

### Fixed
- [#234]: Restore previous MDC values on scope exit
```

### Format B: h3 versions
```markdown
## Change Log

### [Unreleased][unreleased]

### [3.15.0] - 2026-01-29

#### Added
- [#123]: Bind tenant from a verified JWT claim

#### Changed
- [#456]: Cap outbound HTTP timeouts at the remaining deadline

#### Fixed
- [#234]: Restore previous MDC values on scope exit
```

## Reference-Style Links (Recommended)

Use reference-style links for cleaner, more readable entries:

```markdown
#### Fixed
- [#648]: Load the `ScopedValue` backend from the multi-release JAR
- [#625]: Report untrusted inbound context headers instead of binding them

#### Added
- [#629]: Audit executor beans at startup
- [#633]: Compose with existing task decorators

<!-- At the bottom of the file -->
[#648]: https://github.com/boundrail/boundrail-context/issues/648
[#633]: https://github.com/boundrail/boundrail-context/pull/633
[#629]: https://github.com/boundrail/boundrail-context/pull/629
[#625]: https://github.com/boundrail/boundrail-context/pull/625
```

**Benefits:**
- Cleaner to read (no long URLs inline)
- Links defined once, reusable
- Easier to write and maintain

## Version Comparison Links

Add comparison links at the bottom for easy diff viewing:

```markdown
[unreleased]: https://github.com/boundrail/boundrail-context/compare/release-3.15.0...HEAD
[3.15.0]: https://github.com/boundrail/boundrail-context/compare/release-3.14.1...release-3.15.0
[3.14.1]: https://github.com/boundrail/boundrail-context/compare/release-3.14.0...release-3.14.1
```

**Pattern:** `[version]: https://github.com/{owner}/{repo}/compare/{previous-tag}...{current-tag}`

## Section Order

Adapt to existing file, or use this default order:

| Section | When to use |
|---------|-------------|
| Fixed | Bug fixes |
| Changed | Changes to existing functionality |
| Added | New features |
| Deprecated | Soon-to-be removed features |
| Removed | Removed features |
| Security | Vulnerability fixes (CVEs) |

Note: Keep a Changelog uses Added → Changed → Deprecated → Removed → Fixed → Security.

**Rule: Follow existing file's order if present.**

## Mapping Conventional Commits to Changelog

| Commit Type | Changelog Section |
|-------------|-------------------|
| feat | Added |
| fix | Fixed |
| perf | Changed |
| refactor | Changed |
| build(deps) | Changed or Security (if CVE) |
| BREAKING CHANGE | Changed (with bold note) |
| deprecate | Deprecated |

## Workflow

1. **Check for existing CHANGELOG.md**
   ```bash
   cat CHANGELOG.md | head -20
   ```
   Detect format (h2 vs h3 versions, section order, link style).

2. **Determine version range**
   ```bash
   # Find last tag
   git describe --tags --abbrev=0

   # List recent tags
   git tag --sort=-version:refname | head -5
   ```

3. **Get commits since last release**
   ```bash
   git log v3.14.1..HEAD --oneline
   ```

4. **Extract issue/PR references**
   Look for patterns: `#123`, `fixes #123`, `closes #123`, `(#123)`

5. **Generate changelog entry**
   - Group by section
   - Use reference-style links
   - Add version comparison link

6. **Suggest version bump** (based on detected versioning style)

   **SemVer (x.y.z):**
   - BREAKING CHANGE → Major (3.0.0 → 4.0.0)
   - feat → Minor (3.14.0 → 3.15.0)
   - fix only → Patch (3.14.0 → 3.14.1)

   **Two-component (x.y):**
   - BREAKING CHANGE → Major (2.0 → 3.0)
   - feat/fix → Minor (2.1 → 2.2)

   **CalVer (YYYY.MM):**
   - New month → 2026.01 → 2026.02
   - Same month, new release → 2026.01 → 2026.01.1

## Token Optimization

- Use `git log --oneline` for initial scan
- Only fetch full body if BREAKING CHANGE suspected
- Reuse existing link definitions from file
- Don't re-read entire changelog - just prepend new section

## Example: Full Workflow

**Input:** User says "generate changelog for next release"

**Step 1:** Check existing format
```bash
head -30 CHANGELOG.md
```
→ Detects format (e.g. h2 versions, Keep a Changelog order)

**Step 2:** Find version range
```bash
git describe --tags --abbrev=0
```
→ `release-3.15.0`

**Step 3:** Get commits
```bash
git log release-3.15.0..HEAD --oneline
```
→ 5 commits found

**Step 4:** Generate entry
```markdown
### [Unreleased][unreleased]

#### Fixed
- [#650]: Remove ThreadLocal slot when the outermost scope exits

#### Changed
- [#651]: Default executor mode is now `DETACHED`

#### Added
- [#652]: Add Kafka producer and consumer adapters
```

**Step 5:** Generate link definitions
```markdown
[#652]: https://github.com/boundrail/boundrail-context/pull/652
[#651]: https://github.com/boundrail/boundrail-context/pull/651
[#650]: https://github.com/boundrail/boundrail-context/issues/650
```

**Step 6:** Update version comparison links
```markdown
[unreleased]: https://github.com/boundrail/boundrail-context/compare/release-3.15.0...HEAD
```

**Step 7:** Suggest version
```
Suggested: 3.16.0 (minor - has new feature)
```

## Handling Edge Cases

### No conventional commits
List under "Changed" with original message:
```markdown
#### Changed
- Updated context propagation for executors
- Refactored test utilities
```

### Security fix
```markdown
#### Security
- [#618], [#623]: Never write authority to the MDC
```

### Breaking change
```markdown
#### Changed
- **BREAKING**: [#645] Default executor mode is now `DETACHED`
```

### Multiple issues for same fix
```markdown
- [#630], [#631]: Reject expired deadlines at inbound
```

## Integration with Existing CHANGELOG.md

1. **Read existing file** to detect:
   - Heading level (## or ### for versions)
   - Section order
   - Link style (reference or inline)
   - Existing link definitions

2. **Insert new version** after `[Unreleased]` section

3. **Merge link definitions** - add new ones, keep existing

4. **Update `[unreleased]` comparison link** to point to new version

## Quick Reference

| User says | Action |
|-----------|--------|
| "generate changelog" | Full changelog since last tag |
| "changelog since v3.14" | From specific version |
| "what's unreleased" | Preview unreleased changes |
| "update changelog for 3.16" | Generate and insert for version |
| "add changelog entry for #123" | Single issue entry |
