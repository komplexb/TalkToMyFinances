# Research: Readability Libraries vs. On-Device LLM for Grade 6–8 Financial Text

## Executive Summary
In **Talk to My Finances**, financial insights produced by the Auditor Engine must strictly adhere to a **Grade 6–8 reading level (Flesch-Kincaid Grade Level <= 8.0)** to eliminate financial jargon and reduce cognitive friction for everyday consumers.

Currently, the codebase contains a custom-rolled syllable counting regex and Flesch-Kincaid formula in `ReadabilityScorer.kt`. This research investigates:
1. Established, open-source JVM/Kotlin libraries for text readability calculation to replace custom-rolled syllable algorithms.
2. The complementary role of On-Device LLMs (Firebase AI Logic with Gemini Nano) for text rewriting.
3. Alignment with project specs (PRD Section 7, Issue #14, and `GLOSSARY.md`).
4. Recommended refactoring strategy and retry/fallback best practices.

---

## 1. Primary Source & Spec Requirements

### 1.1 Project Specs & Glossary Commitments
- **Issue #14 (PRD 1–3 Spec):**
  > *"Readability Fallback (Hybrid Inference): The Android app will orchestrate an HTTP call to the Python Engine for data, and then use Firebase AI Logic locally on-device to rewrite the returned text to a Grade 6-8 reading level if it fails initial validation, minimizing latency."*
- **GLOSSARY.md:**
  > *"Hybrid Inference: The mechanism used during the Custom Question Loop where the Android app orchestrates an HTTP call to the Python Engine for data, and then uses Firebase AI Logic on-device to rewrite the returned text to a Grade 6-8 reading level, minimizing latency."*
- **PRD Section 7.2:**
  > *"Target Standard: Grade 6–8 (Year 7–8 Australian reading level), aligning with a Flesch-Kincaid Grade Level of 6.0–8.0. To guarantee outputs consistently maintain this accessible tone... combine programmatic guardrails with automated LLM evaluations."*

---

## 2. Evaluation of Readability Libraries vs. Custom Scoring vs. On-Device LLM

### 2.1 Why Avoid Rolling a Custom Syllable Counter?
Custom syllable counting using regular expressions (e.g. `Regex("[aeiouy]+")`) suffers from persistent edge-case failures when encountering complex financial terminology (*amortization, deceleration, expenditure, discretionary, liability, annuity*). Silent miscounts shift calculated Flesch-Kincaid grade levels by 2–4 grades, leading to false positives or false negatives.

### 2.2 Open-Source JVM Readability Libraries
Instead of rolling custom math, standard open-source libraries provide well-tested implementations:

1. **`readability4j` (Pure Kotlin / Java)**
   - **Repository:** `ashleyf/readability4j` / `io.github.mflsariva:readability4j`
   - **Features:** Provides Flesch-Kincaid Grade Level, Flesch Reading Ease, Gunning Fog, Coleman-Liau, SMOG, and ARI.
   - **Pros:** Written in Kotlin/Java with zero native dependencies; light footprint (~30KB); includes comprehensive English syllable rules and exception dictionaries.
2. **`textstat-java`**
   - **Features:** Direct Java port of Python's popular `textstat` library.
   - **Pros:** Proven consistency with Python backend textstat evaluation tools.

### 2.3 On-Device LLM (Firebase AI Logic Gemini Nano)
An On-Device LLM (`com.google.firebase:firebase-ai` using `OnDeviceConfig(mode = InferenceMode.PREFER_ON_DEVICE)`) serves a fundamentally different purpose:
- **What it cannot do:** It is a generative neural model and cannot deterministically calculate a statistical formula score like `Flesch-Kincaid = 6.4`.
- **What it excels at:** It performs zero-latency local text transformation, rewriting jargon-heavy text into plain English using target prompts (*"Rewrite this text at a Grade 6-8 reading level using short active sentences..."*).

---

## 3. Comparison Matrix

| Dimension | Custom Regex (`ReadabilityScorer.kt`) | Established Library (`readability4j`) | On-Device LLM (`FirebaseAI` Gemini Nano) |
| :--- | :--- | :--- | :--- |
| **Syllable Accuracy** | Low (fails on complex words) | **High (uses dictionary rules)** | N/A (Generative Transformer) |
| **Primary Role** | Metric Estimation | **Metric Measurement (Scoring)** | **Text Transformation (Rewriting)** |
| **Execution Latency** | < 1ms | **< 1ms** | 100ms – 300ms |
| **Network Dependency**| Zero | Zero | Zero |
| **Spec Alignment** | Maintenance risk | Fits "Initial Validation" gate | Fits "On-Device Rewriter" fallback |

---

## 4. Architectural Recommendation

Maintain the **Hybrid Inference** architecture defined in Issue #14 and `GLOSSARY.md`, but replace the internal syllable counting logic:

```text
               Raw Insight Text from Cloud Run Backend
                                  │
                                  ▼
                   ┌─────────────────────────────┐
                   │ Stage 1: Readability Engine │
                   │ (using readability4j lib)   │
                   └──────────────┬──────────────┘
                                  │
                  ┌───────────────┴───────────────┐
                  │                               │
           Grade <= 8.0                   Grade > 8.0
         (Passes Baseline)              (Fails Baseline)
                  │                               │
                  ▼                               ▼
          Return Original Text            ┌─────────────────────────────┐
          (Zero LLM Latency)              │ Stage 2: On-Device LLM      │
                                          │ (Firebase AI Logic Nano)    │
                                          │ Rewrite at Grade 6-8 Level  │
                                          └──────────────┬──────────────┘
                                                         │
                                                         ▼
                                                Return Rewritten Text
```

---

## 5. Retry Limits & Quality Guardrail Best Practices

### 5.1 Retry Limit Cap: `maxAttempts = 1` (or Max `2`)
- **Latency Budget**: On-device model inferences take ~100–300ms. Multiple retries compound UI render delays on the Insights screen.
- **Best Practice**: Cap retries at **1 attempt** (or maximum **2**). If a single prompt rewrite does not achieve Grade <= 8.0, subsequent attempts with identical prompts yield diminishing returns.

### 5.2 Monotonic Quality Selection ("Best Candidate So Far")
- If an LLM rewrite improves the score from Grade 12.0 down to Grade 8.8 (still above target 8.0), **keep the Grade 8.8 version** rather than reverting to the original Grade 12.0 text.
- `ReadabilityGuard` tracks `best` and `bestGrade` across attempts:
  ```kotlin
  if (candidateGrade < bestGrade) {
      best = candidate
      bestGrade = candidateGrade
  }
  ```

### 5.3 Mandatory Fact Preservation Guardrail (Number Protection)
- **Critical Financial Safety Risk**: A generative model simplifying text might hallucinate or drop numerical facts (e.g. rewriting *"Your utility bills came in $140 lower this quarter"* as *"Your bills were much lower"* or *"Your bills were $100 lower"*).
- **Rule**: Compare numeric tokens (`Regex("\\$?\\d+([.,]\\d+)?")`) between original and rewritten candidate. If the candidate drops or changes any number, **reject the candidate immediately** and fall back to the last valid text state.

### 5.4 Graceful Degradation Strategy
If the on-device model is unavailable, offline, or throws an exception, **always return the best valid text available** (or original backend text). The app must never display blank cards, error messages, or crash due to readability rewriting failures.
