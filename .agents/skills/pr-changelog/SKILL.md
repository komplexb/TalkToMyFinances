---
name: pr-changelog
description: Write the "User facing changelog" section of a PR description.
metadata:
    credits:
    skill: pr-changelog
    author: Byron Buckley
---

# User-facing changelog

## Repo context (auto-loaded)

- Current branch: !`git branch --show-current`
- Merge-base with develop:
  !`git fetch origin develop 2>/dev/null; git merge-base origin/develop HEAD 2>/dev/null || git merge-base develop HEAD`

PR descriptions on this repo open with a "User facing changelog" section. Reviewers and release captains skim
this section first to understand scope. The goal of this skill is to produce a short, scannable changelog — written so
someone who hasn't seen the code can follow it — that answers four questions in order: **what**, **why**, **limitations
/ out-of-scope**, **breaking change**. See Style rules below for how each section should read.

## When to invoke

- The user asks for a "changelog message", "changelog blurb", "release notes for this PR", or similar.
- The user pastes a PR template containing an angle-bracket placeholder like
  `<Description - what and why this PR is being created. Describe limitations/ breaking change if any>` and wants it
  filled in.
- A multi-commit branch is ready for PR and the user asks "what should the PR description say?" without further
  qualifiers.

If the request is for a single-line git commit message, this skill does **not** apply — use the repo's commit-message
convention (`<type>(<ticket>): <short description>`) instead.

## Output structure

Use this exact template. Keep the heading at H2 (`##`) so it renders cleanly inside the PR description. Write each section's content per the Style rules below.

```markdown
### What

<What>

### Why

<Why>

### Limitations / left intentionally unchanged

<Limitations — omit this entire section if there's nothing non-obvious to flag>

### Breaking change

<Breaking change>
```

The Limitations section is the only optional part of the template — include it only when there's a non-obvious omission
a reviewer would otherwise raise as a comment; a "TODO next PR" counts as one, general future work does not. Everything
else in the template is always present, even when the content is short (`Breaking change` is frequently just "None.").

## Style rules

- **Prose, not fragments.** Write full readable sentences a reader skims in one pass, not terse keyword-dense notes. The
  "instead of X, we now do Y, so that Z" shape reads well for a fix.
- **Behaviour over mechanism, grouped by capability.** Say what the user can now do or see, not which file, function, or
  attribute changed — that's recoverable from the diff. Group **What** by capability, not by file; if a reviewer needs
  exact locators, that belongs in a separate notes/test-plan section. For **Why**, lead with the reason the change is
  needed (the bug, the constraint, the ticket), not the mechanism — if it's a follow-up to another ticket/PR, name it by
  ID.
- **Bullet-per-capability once a section covers 2+ distinct things.** This applies to every section — What, Why,
  Limitations, Breaking change alike. A single capability or reason still reads as a plain prose paragraph (see the
  worked example below). Once a section would otherwise merge two or more distinct capabilities/reasons/limitations into
  one paragraph, switch to a bullet list instead — one full sentence (or two) per bullet, each still following the
  "prose, not fragments" rule; a fragment inside a bullet is still a fragment. Use a nested sub-bullet only for a
  supporting detail of the bullet above it (e.g. naming the specific starter cases seeded), not as a way to cram in
  another top-level capability. Don't force a bullet list onto a section that only has one thing to say — that just adds
  visual noise.
- **Defer jargon.** Reach for a precise technical term only when the precision is the point; otherwise use the plain
  phrasing. Prefer the user-visible name of a thing ("the Add another button") over its internal one.
- **Tense and voice.** Past tense for what shipped ("Rolled out the IMO sequencing pattern…"), present tense for current
  behaviour ("Spaces now run on the prod bundle until auth completes."). Avoid future tense — by the time the PR is
  reviewed the work is done.
- **No marketing copy.** Reviewers are engineers reading 8 PRs in a row. Cut adjectives ("comprehensive", "robust",
  "elegant") and avoid restating the obvious.
- **No code fences inside the four sections.** Inline backticks for identifiers are fine, but multi-line code blocks
  belong in a separate "Test plan" or "Notes" section if the template has one.
- **Breaking-change default is "None."** One sentence stating what breaks and a pointer to the migration step if there
  is one; don't pad the section with "no API changes, no schema changes…" when there isn't. If there's any ambiguity,
  err on the side of calling it out — silently breaking consumers is the failure mode this section exists to prevent.
- **ASCII punctuation only.** Never use em dashes, en dashes, or curly/smart quotes anywhere in the four sections. Use a
  plain hyphen (`-`) and straight quotes (`'`, `"`) only.

## Worked example (an accessibility fix)

```markdown
### What

Adding an item to a collection is now announced reliably to screen-reader users. When you grow a collection — for example by selecting "Add another" — assistive technology such as VoiceOver consistently reads out that new questions have appeared on the page.

### Why

A follow-up to EX-3378, which first introduced these announcements. The announcement was always placed on the page, but VoiceOver often skipped it when a new item was added: selecting "Add another" briefly moved focus away at the moment the announcement was made, so the screen reader read whatever it landed on instead. The button now keeps its place while the item is added, so the announcement is heard. Nothing changes for sighted users, and the safeguard that stops a second item being added before the first finishes saving is still in place.

### Breaking change

None.
```

## Sourcing the content

If the conversation already contains the work (recent diffs, commit list, ticket context), draft from that — don't ask
the user to re-paste it. The "Repo context" section above already gives you the current branch — don't re-run
`git branch --show-current` yourself. Concretely:

1. **Ticket ID and title** — pull from the branch name in the Repo context above
   (`chore/regression-visit-with-importmaps-EX-3507` → `EX-3507`) or the most recent commit subjects. If multiple
   tickets are touched, use the umbrella ticket and mention the others in **What**.
2. **What** — use the merge-base SHA from Repo context above as `<base>` (don't re-derive it; that value already fetched
   `develop` and resolved the fallback). Read `git log --oneline <base>..HEAD` and `git diff --stat <base>..HEAD` across
   the _whole_ range to identify the affected areas. Group by directory or feature rather than listing files.
3. **Why** — prefer the ticket description if available (read it via the Jira MCP if the ID is known), otherwise infer
   from the predecessor PR / parent ticket referenced in commits.
4. **Limitations** — scan for things in the affected areas that look related but weren't touched. These are usually
   known to you from the work itself; if not, a quick `grep` for the pattern being migrated will surface them.
5. **Breaking change** — default to "None." Flip only when there's a removed export, a renamed prop, a schema change, or
   a behavioural change that downstream consumers would feel.

Hand the result back as a single markdown block ready to paste into the PR description. Do not wrap it in additional
commentary unless the user asks.

## Self-check before delivering

A style rule stated once in prose is easy to drop while composing free-form text — the writer fills in the template and
moves on. Before handing the draft back, re-scan it against this checklist explicitly; don't rely on having applied the
rules correctly the first time:

1. **Bullet-per-capability.** For each of What, Why, Limitations (if present), and Breaking change: count the distinct
   capabilities/reasons/items it states. 2 or more → it must be a bulleted list (one full sentence, or two, per bullet);
   if the draft has it as a prose paragraph, rewrite it now. Exactly one → it must stay a plain prose paragraph; don't
   bullet a single item.
2. **ASCII punctuation.** No em dashes, en dashes, or curly/smart quotes anywhere in the four sections — plain hyphens
   and straight quotes only.
3. **No leftover template tokens** (`<What>`, `<Why>`, etc.) and no code fences inside the four sections.

Only after this pass proceed to "Check for an existing PR."

