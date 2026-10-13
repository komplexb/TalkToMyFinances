---
name: build-this
description: "Workflow harness to implement an issue or spec using /implement with TalkToMyFinances rules."
disable-model-invocation: true
---

# Build This

Workflow harness for implementing a task from an issue URL or number.

To begin, branch from the current branch with the pattern `spec/<section-or-issue-number>-<hyphenated-issue-title>`.

## Workflow Rules

Guide the implementation with these project rules:

- **Grounding**: Do not rely on raw model knowledge alone. Leverage the Android and Firebase skills (`.agents/skills/firebase-*`, Android skills). When unclear, read:
  - `docs/**/*`
  - The PRD (`docs/Product/`)
  - Open issues and specs (`gh issue list --label ready-for-agent`)
  When ambiguous, the spec(s) supersede the PRD as they are derived from the PRD then refined.

- **No handrolling**: Avoid handrolling solutions that can be solved by an established library. Before writing custom algorithms, complex regex, parsing logic, or scoring/math heuristics, check Maven Central/PyPI for battle-tested libraries.

- **No stubs/mocks outside tests**: Avoid stubs and mocks outside of test suites, unless a dependency has not yet been built. Whenever tempted to reach for a stub/mock, check the `backend/` directory to see if it already exists.

- **Tests & regressions**: Ensure appropriate tests are developed for the features, and existing tests are run at the end of the dev cycle to prevent regressions.

- **How to deploy**: Add or extend a "How to deploy" section outlining steps needed for prod (`firebase-tools`, console actions, etc.).

## Execution

Call the Skill tool with `implement` (or run `/implement`) with the ticket reference, adhering strictly to the workflow rules above.
