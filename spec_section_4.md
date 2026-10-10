## Problem Statement

Everyday banking consumers need a seamless, latency-free conversational experience with their financial data. They need complex, out-of-catalog math questions answered deterministically without exposing sensitive PII to external models, and they need dynamic visual data delivered alongside voice responses without waiting for extra loading spinners.

## Solution

"Talk to My Finances" uses a multi-agent, hybrid cloud architecture. An Android client makes on-demand REST calls to a Cloud Run backend, where the Auditor Engine scrubs PII and computes Verified Facts over a synthetic dataset. A Curation Agent running in the same Cloud Run service immediately selects the 3 most relevant Chips and returns them to the client. For out-of-catalog math, Gemini acts as an Intent Parser to output a `FinancialQuerySpec` for deterministic computation (the Parser-Spec-Compute pattern). Finally, the Android app leverages Firebase AI Logic to manage the Gemini Live voice interactions, rendering Visual Cards instantly using full JSON payloads passed directly through tool calls.

## User Stories

1. As a user navigating to the Insights Screen, I want my data to be analyzed on-demand, so that my insights are based on the absolute freshest data available.
2. As a privacy-conscious user, I want my PII to be scrubbed before any language models see it, so that my financial identity remains secure.
3. As a user, I want my Insights Screen to load quickly, so that I don't have to wait for multiple network requests to finish before seeing my top 3 Chips.
4. As a user asking a complex "What-If" math question, I want the system to calculate the answer deterministically based on my actual ledger, so that the financial insights I receive are mathematically accurate and not hallucinated.
5. As a user chatting in Voice Mode, I want the conversational agent to instantly offload complex visual breakdowns to my screen, so that I don't have to listen to the AI read long lists of numbers.
6. As a user viewing a newly rendered Visual Card on my screen, I want the chart to appear instantly alongside the voice confirmation, so that I am not left waiting for a secondary database fetch to complete.

## Implementation Decisions

- **Auditor Engine Execution**: The Android client triggers the Cloud Run Auditor Engine on-demand via a REST call during the onboarding loading screen.
- **PII Scrubbing**: Handled directly in the Cloud Run backend upon ingestion, before any LLM processing.
- **Curation Agent Location**: The Curation Agent runs synchronously in Cloud Run immediately following the Auditor Engine's math calculations. The HTTP response to the client contains the 3 ready-to-display Chips.
- **Out-of-Catalog Math**: Handled via the Parser-Spec-Compute pattern. The LLM acts as an Intent Parser to populate a `FinancialQuerySpec`, which the Auditor Engine executes deterministically.
- **Gemini Live & Firebase AI Logic**: The Android client strictly uses Firebase AI Logic to manage the Gemini Live features (multimodal voice interactions and tool calling).
- **Visual Offloading Payload**: When Gemini Live invokes the `render_visual_card` tool call, the payload contains the full JSON data required to render the Visual Card, avoiding a secondary database fetch from the client.

## Testing Decisions

Good tests will focus on testing the external behaviors and boundaries between the Android app, the Firebase AI Logic SDK, and the Cloud Run backend.

- **Cloud Run Auditor & Curation Boundary (HTTP REST API Seam)**: We'll test this by sending mock HTTP requests to the Cloud Run endpoint and asserting that it returns exactly 3 expected Chips, validating both the Auditor Engine's math and the Curation Agent's selection logic in one go.
- **Parser-Spec-Compute Boundary (Intent Parser Seam)**: We'll test this by passing mock user questions (e.g., "What if I spend $50 less?") and asserting the resulting `FinancialQuerySpec` matches the expected schema and constraints.
- **Firebase AI Logic Client-Side Tool Calling Boundary (Gemini Live Seam)**: We'll test this by injecting a mock `render_visual_card` tool call (containing the full JSON data payload) into the Firebase AI Logic WebSocket receiver and asserting the Android app renders the Visual Card without attempting a secondary network fetch.

## Out of Scope

- The generation algorithms and Python code for the 10-Fact deterministic catalog.
- Direct BankSync API integration (we will use the hardcoded synthetic dataset).

## Further Notes

None.

