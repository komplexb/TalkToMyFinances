# Talk to My Finances — Agent Guidelines

## Agent skills

### Issue tracker

Issues are tracked on GitHub via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Canonical triage label vocabulary. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout (`GLOSSARY.md` + `docs/adr/`). See `docs/agents/domain.md`.

### Product docs

- PRD and designs live in `docs/Product`
- Hackathon Guidelines live in `docs/AIBuilderOfficial`

## Tech Stack Constraints

- **Platform**: Native Android
- **Language**: Kotlin
- **UI**: Jetpack Compose (implied by modern native Android, but noting Kotlin and Native Android are mandatory as per repository scan).

## Before you build or ask

Before implementing, or asking the user a design question, read `docs/**/*` and every open `ready-for-agent` issue (`gh issue list --label ready-for-agent --state open`), not just the issue you were given. Specs depend on each other. Ask the user only about what these sources don't answer, and state which sources you checked and what you recommend.

When planning or implementing, always leverage /.agent/skills/firebase-* skills
