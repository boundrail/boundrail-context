# AGENTS.md

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines, and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multistep tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

## 5. Project Documents

- The source of truth for behaviour is `specs/###-feature-name/spec.md` (one feature per milestone, GitHub Spec Kit layout).
  `.specify/memory/constitution.md` holds the principles and spec conventions; `docs/design.md` explains why; `docs/roadmap.md` defines the
  milestones and traceability.
- Follow the working agreement in roadmap.md: one feature at a time, in number order, starting from its `spec.md`, `plan.md` and `tasks.md`.
- Never implement behaviour the spec doesn't describe. Change the spec first, then the tests, then the code.
- Skills for any code change, inside or outside the agents: `test-driven-development` before writing code, `systematic-debugging` for any
  failure, `verification-before-completion` before claiming anything works, and `requesting-code-review` after each task and before a
  feature closes (the `reviewer` agent does the review).
- Implementation starts only after the user approves the feature's spec, plan, tasks and TDD exceptions. The `leader` agent then commits
  each reviewed task on the feature branch; pushing and merging always need the user.
