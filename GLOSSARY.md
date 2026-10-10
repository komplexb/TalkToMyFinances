# Talk to My Finances

Voice-first and conversational financial coach that eliminates jargon and presents actionable, mathematically verified insights from banking data.

## Language

**Verified Fact**:
A deterministic, mathematically calculated financial metric produced exclusively by the Python auditor engine.
_Avoid_: Insight, analysis, LLM summary

**Chip**:
One of three curated proactive prompt cards displayed on the home canvas. These are allocated using a "2+1" model: exactly 2 slots are reserved for Verified Facts directly related to the user's active goal, and 1 slot for a "Serendipity" fact (the highest magnitude positive win from any other category).
_Avoid_: Suggestion card, prompt card, recommendation

**Auditor Engine**:
The pure Python data layer responsible for PII scrubbing, normalization, and deterministic calculation of Verified Facts.
_Avoid_: Backend, API server

**Visual Card**:
An inline visual chart or breakdown rendered on the client UI via the `render_visual_card` tool call.
_Avoid_: Chart widget, visual payload

**Audit Receipt**:
A concise, plain-English summary accompanying a calculation that transparently states verified math assumptions (active months counted, exclusions applied, and denominator used).
_Avoid_: Explanation, math breakdown

**Insights Screen**:
The primary dashboard screen hosting the 3 proactive Prompt Chips dynamically selected from the 6-tier recency spectrum.
_Avoid_: Home canvas, feed

**Tête-à-Tête Chat Screen**:
The dedicated conversation and voice screen hosting chat message bubbles, inline Visual Cards, and the dual-mode voice toggle bar.
_Avoid_: Chat view, live screen

**Hybrid Inference**:
The mechanism used during the Custom Question Loop where the Android app orchestrates an HTTP call to the Python Engine for data, and then uses Firebase AI Logic on-device to rewrite the returned text to a Grade 6-8 reading level, minimizing latency.
_Avoid_: Local rewriting, fallback generation

**Parser-Spec-Compute**:
The architectural pattern for out-of-catalog financial queries where an LLM (Intent Parser) maps a user query into a structured `FinancialQuerySpec`, which is then executed deterministically by the Auditor Engine.
_Avoid_: On-the-fly code generation, prompt-based math

**FinancialQuerySpec**:
A strongly-typed schema (capturing target metric, entity inclusions/exclusions, temporal grain, and denominator policy) populated by the LLM and executed by the Auditor Engine.
_Avoid_: JSON payload, function call args
