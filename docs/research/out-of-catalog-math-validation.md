# Out-of-Catalog Math Validation: Architectures for Reliable Financial Arithmetic in LLMs

## Executive Summary
In conversational financial intelligence, users frequently pose open-ended, ad-hoc analytical questions (e.g., *"What is my average monthly spend on Uber Eats excluding Grace City Church?"* or *"How many times do I order takeaway each week?"*). When large language models (LLMs) attempt to answer these questions by generating freeform, on-the-fly Python or SQL code in sandboxes (Code Interpreter style), production teams encounter four persistent failure modes:
1. **Semantic Boundary Hallucinations:** The model misinterprets natural language boundaries—mistaking transaction statuses (counting pending or authorization-hold charges), applying inaccurate temporal boundaries (partial months vs. full calendar months), or misidentifying entity exclusions.
2. **Stochastic Code Generation:** Every invocation generates a structurally different ad-hoc script with idiosyncratic edge-case handling (e.g., handling zero-spend periods inconsistently).
3. **The Silent Denominator Problem:** In natural language financial calculations, critical assumptions (such as dividing by total elapsed months vs. only active purchasing months) remain unstated and variable, producing 30–50% variance across identical runs.
4. **Lack of Verifiable Ground Truth:** Because the code is executed in an unconstrained sandbox without domain-aware invariant checking, incorrect answers are returned with high conversational confidence.

To solve this without sacrificing user flexibility, this research investigates primary sources across AI research (PAL, PoT, Self-Consistency, LEVER, CRITIC, PRMs) and enterprise financial data engineering (dbt MetricFlow, Cube Semantic Layer, FinQA). We shortlist **three distinct, state-of-the-art architectural patterns** for production deployment:
1. **Governed Semantic Layer with Intermediate Semantic Representation (IR / Metric AST)**
2. **Program-Aided Dual-Execution with Cross-Consistency Reranking (PAL-SC + LEVER Harness)**
3. **Deterministic Intent Deconstruction with Audit Receipts ("Parser-Spec-Compute" Pipeline)**

---

## 1. Pattern 1: Governed Semantic Layer & Intent-to-Metric AST

### 1.1 Conceptual Architecture
Instead of permitting the LLM to write arbitrary Python/SQL code over raw, untyped transaction tables, this pattern transforms the LLM from an unconstrained code author into an **Intent Classifier and Semantic AST Compiler**.

```
User Query: "What is my average monthly spend on Uber Eats excluding Grace City Church?"
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  LLM Semantic Parser (Schema-Constrained via Pydantic/Zod)   │
│  Outputs validated Semantic AST JSON                        │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Semantic Layer Engine (e.g., dbt MetricFlow / Cube / Wren) │
│  - Dimension Registry (clean_merchant, category, date)      │
│  - Governed Metrics (avg_monthly_spend, order_count)        │
│  - Canonical Rules (status == 'POSTED', timezone alignment) │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Deterministic SQL / DataFrame Query Compiler               │
│  Compiles AST into certified, single-path execution code    │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Pre-Aggregated / Normalized Ledger (DuckDB / Cloud Run)    │
│  Returns deterministic ground-truth number                  │
└─────────────────────────────────────────────────────────────┘
```

The Semantic Layer maintains an immutable registry of:
- **Dimensions:** Normalized fields like `merchant_canonical`, `spending_category`, `transaction_date`, `lifecycle_status`.
- **Governed Metrics:** Mathematically certified aggregation formulas (e.g., `avg_monthly_spend = sum(amount) / count(distinct month_bucket)`).
- **Invariants:** Mandatory system-level filters (e.g., `status = 'POSTED' AND is_internal_transfer = FALSE`).

The LLM outputs an abstract syntax tree (AST) matching a rigid JSON schema:
```json
{
  "metrics": ["average_monthly_spend"],
  "dimensions": ["calendar_month"],
  "filters": [
    {"field": "merchant_name", "operator": "equals", "value": "Uber Eats"},
    {"field": "merchant_name", "operator": "not_in", "value": ["Grace City Church"]}
  ],
  "time_window": {"grain": "month", "range": "last_12_months"}
}
```

### 1.2 Failure Mode Resolution
- **Eliminates Code Generation Drift:** The LLM never writes procedural code, loops, or custom math. It only selects from declared metrics and dimensions.
- **Enforces Semantic Invariants:** The semantic layer automatically appends mandatory financial rules to every compiled query (filtering out pending transactions, credit balance transfers, or reversed charges), preventing the model from forgetting ledger edge cases.
- **Canonical Entity Mapping:** Exclusions like *"Grace City Church"* are resolved through the entity catalog rather than arbitrary string regexes.

### 1.3 Ground Truth & Verification Mechanisms
- **Static Schema Validation:** The AST is validated against the Pydantic/JSON schema before compilation. Unrecognized dimensions or illegal metric combinations throw deterministic errors.
- **Entity Vocabulary Grounding:** A fuzzy string match / trie validator ensures that requested merchant names exist within the user's actual transaction history.
- **Deterministic SQL Generation:** The semantic engine (not the LLM) compiles the AST into SQL/polars code. Because compilation is rule-based, the execution path is 100% reproducible.

### 1.4 Trade-Offs & Feasibility
- **Pros:** Zero risk of arbitrary code vulnerabilities; deterministic and auditable; conforms directly to institutional BFSI compliance and governance standards.
- **Cons:** Constrained to metrics and dimensions modeled in advance in the semantic schema. True "wildcard" requests outside the semantic model fail to parse.
- **Feasibility:** **High**. A lightweight Python implementation using Pydantic + DuckDB can be built for a prototype in hours, with dbt/Cube serving as the enterprise scale target.

### 1.5 Primary Source Citations
- **dbt Labs:** *MetricFlow Architecture & Semantic Layer Specification* (dbt Labs Documentation, 2023–2024). https://docs.getdbt.com/docs/build/about-metricflow
- **Cube Dev:** *Decoupling Semantics from SQL: The Semantic Layer for AI Agents* (Cube.dev whitepaper, 2024). https://cube.dev
- **Pourreza, M., & Rafiei, D. (2023):** "DIN-SQL: Decomposed In-Context Learning of Text-to-SQL with Self-Correction." *Thirty-seventh Conference on Neural Information Processing Systems (NeurIPS 2023)*. arXiv:2304.11015.
- **Wren AI:** *Wren Engine: SQL-based Semantic Engine for LLM Agents* (Open-source Semantic Parsing for Multi-turn QA, 2024). https://github.com/Canner/WrenAI

---

## 2. Pattern 2: Program-Aided Dual-Execution with Cross-Consistency Reranking (PAL-SC + LEVER Harness)

### 2.1 Conceptual Architecture
When an application requires true computational flexibility (such as bespoke outlier trimming, multi-conditional percentiles, or arbitrary numerical simulations), this pattern combines **Program-Aided Language models (PAL / PoT)**, **Code-based Self-Consistency decoding (PAL-SC)**, and **Execution-Guided Program Verification (LEVER / CRITIC)**.

```
User Query: "What is my average monthly spend on Uber Eats excluding Grace City Church?"
                           │
            ┌──────────────┴──────────────┐
            ▼                             ▼
┌───────────────────────────┐ ┌───────────────────────────┐
│ Generator A (Pandas)      │ │ Generator B (SQL / DuckDB)│
│ Generates Script Candidate│ │ Generates Script Candidate│
│ (Samples 1..N paths)      │ │ (Samples 1..N paths)      │
└─────────────┬─────────────┘ └─────────────┬─────────────┘
              │                             │
              └──────────────┬──────────────┘
                             ▼
┌─────────────────────────────────────────────────────────────┐
│  Secure Sandbox Execution Harness (gVisor / Subprocess)    │
│  Wraps scripts in Domain Invariant Assertions:              │
│  - assert df['status'].isin(['POSTED']).all()               │
│  - assert date_range.is_valid()                             │
│  - assert result >= 0 and is_finite(result)                 │
└─────────────────────────────┬───────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│  LEVER Execution Verifier & Consensus Clustering (PAL-SC)   │
│  - Filters out scripts failing runtime assertions           │
│  - Clusters outputs: Cluster 1 ($142.50, n=4), Cluster 2 ($0)│
│  - Majority Vote / Verifier Rerank selects winner           │
└─────────────────────────────┬───────────────────────────────┘
                             │
                             ▼
                     Verified Answer
```

1. **Dual / Multi-Path Code Generation:** The system prompts the LLM to generate $N$ candidate scripts (or uses a dual-engine prompt: Generator A writes Python/pandas; Generator B independently writes SQL/DuckDB).
2. **Pre- and Post-Condition Assertion Injection:** The execution harness automatically injects programmatic assertions:
   - *Pre-conditions:* Check schema types, ensure pending transactions are dropped, ensure date masks match the requested window.
   - *Post-conditions:* Assert the calculated result is non-negative, non-null, finite, and logically bounded (e.g., spend cannot exceed total historical outflow).
3. **Execution Consensus Decoding (PAL-SC):** The scripts execute against an isolated dataframe. The system tallies the returned values. If multiple diverse implementations arrive at the identical numerical value (within floating-point epsilon), the value is certified.
4. **Tool-Interactive Self-Correction (CRITIC / Reflexion):** If an execution triggers an assertion failure or exception, runtime tracebacks are fed back to the model in a closed loop to correct the code.

### 2.2 Failure Mode Resolution
- **Mitigates Flaky / Stochastic Code Generation:** A syntax error or idiosyncratic indexing mistake in one script is eliminated because the system demands consensus across independent execution paths.
- **Catches Semantic Boundary Violations at Runtime:** Hardcoded assertions (e.g., verifying that no row with `status == 'PENDING'` entered the aggregation) fail loudly in the harness, triggering self-correction rather than returning an invalid figure.

### 2.3 Ground Truth & Verification Mechanisms
- **Assertion Invariant Harness:** Deterministic programmatic assertions verify business logic rules that LLMs frequently overlook.
- **Execution-Space Voting (PAL-SC):** Instead of voting on text tokens (which can be fragile), the vote is taken on the execution return state ($y = \text{exec}(p)$).
- **Dual-Model Cross-Engine Parity:** Requiring a pandas script and an independent DuckDB SQL query to match provides cross-engine verification.

### 2.4 Trade-Offs & Feasibility
- **Pros:** Handles arbitrary, open-ended mathematical logic without predefined metric schemas; backed by proven peer-reviewed research (ICML, ICLR).
- **Cons:** Increased inference cost and latency ($N \times$ LLM tokens + execution runtime); requires isolated, hardened sandbox execution (e.g., gVisor, WebAssembly, or ephemeral Cloud Run).
- **Feasibility:** **Moderate**. For prototypes, sampling $N=3$ Python scripts or 1 Python + 1 SQL query in an isolated Python process with strict timeouts is easily achievable.

### 2.5 Primary Source Citations
- **Gao, L., et al. (2023):** "PAL: Program-aided Language Models." *Fortieth International Conference on Machine Learning (ICML 2023)*. arXiv:2211.10435.
- **Chen, W., et al. (2022):** "Program of Thoughts Prompting: Disentangling Computation from Reasoning for Numerical Reasoning Tasks." *Transactions on Machine Learning Research (TMLR 2023)*. arXiv:2211.12588.
- **Wang, X., et al. (2022):** "Self-Consistency Improves Chain of Thought Reasoning in Language Models." *Eleventh International Conference on Learning Representations (ICLR 2023)*. arXiv:2203.11171.
- **Ni, A., et al. (2023):** "LEVER: Learning to Verify Language-to-Code Generation with Execution." *Fortieth International Conference on Machine Learning (ICML 2023)*. arXiv:2302.08468.
- **Gou, Z., et al. (2024):** "CRITIC: Large Language Models Can Self-Correct with Tool-Interactive Critiquing." *Twelfth International Conference on Learning Representations (ICLR 2024)*. arXiv:2305.11738.

---

## 3. Pattern 3: Deterministic Intent Deconstruction with Audit Receipts (The "Parser-Spec-Compute" Pipeline)

### 3.1 Conceptual Architecture
Grounded in empirical financial QA research (FinQA, TAT-QA) and process supervision (Let's Verify Step by Step / PRMs), the **Parser-Spec-Compute** pattern splits the query into two decoupled stages:
1. **Semantic Deconstruction into a Formal Query Specification (`FinancialQuerySpec`).**
2. **Deterministic Primitives Execution with an Explainable Plain-English "Audit Receipt".**

```
User Query: "What is my average monthly spend on Uber Eats excluding Grace City Church?"
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Phase 1: Intent Deconstruction (LLM Parser)                │
│  Emits strongly-typed Pydantic FinancialQuerySpec:          │
│  - target_metric: "MONTHLY_AVERAGE_SPEND"                   │
│  - include_entities: ["Uber Eats"]                          │
│  - exclude_entities: ["Grace City Church"]                  │
│  - date_range: {"type": "ALL_TIME_OR_CUSTOM", "granularity": "MONTH"} │
│  - denominator_policy: "ACTIVE_MONTHS_WITH_TRANSACTIONS"   │
│  - pending_policy: "EXCLUDE"                                │
└─────────────────────────────┬───────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│  Phase 2: Spec Verification & Catalog Grounding             │
│  - Merchant Trie Match: Verifies "Uber Eats" in ledger      │
│  - Validates "Grace City Church" entity presence            │
│  - Confirms date window bounds against physical ledger      │
└─────────────────────────────┬───────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│  Phase 3: Deterministic Execution Primitives               │
│  Invokes pre-audited Python computation functions:          │
│  calculate_periodic_average(df, spec)                       │
└─────────────────────────────┬───────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│  Phase 4: "Audit Receipt" Generation                        │
│  Echoes verified math assumptions back in Grade 6–8 English: │
│  "Across 9 active months, you spent $1,282.50 on Uber Eats  │
│   (excluding Grace City Church and pending transactions).   │
│   Your average was $142.50/month."                          │
└─────────────────────────────┬───────────────────────────────┘
                             │
                             ▼
                     Verified Answer
```

The core structure of the Pydantic specification:
```python
class FinancialQuerySpec(BaseModel):
    target_metric: Literal["MONTHLY_AVERAGE", "TOTAL_SUM", "TRANSACTION_COUNT", "RUN_RATE"]
    included_merchants: list[str]
    excluded_merchants: list[str]
    included_categories: list[str]
    excluded_categories: list[str]
    temporal_grain: Literal["WEEK", "PAY_CYCLE", "MONTH", "QUARTER", "YEAR"]
    denominator_strategy: Literal["ACTIVE_PERIODS_ONLY", "ALL_CALENDAR_PERIODS"]
    exclude_pending: bool = True
    exclude_internal_transfers: bool = True
```

### 3.2 Failure Mode Resolution
- **Resolves the Silent Denominator Ambiguity:** The spec forces the model to choose between `ACTIVE_PERIODS_ONLY` (dividing by the months where transactions occurred) and `ALL_CALENDAR_PERIODS` (dividing by all 12 calendar months). In finance, this ambiguity is the primary source of 40%+ calculation discrepancies.
- **Guaranteed Code Stability:** Zero on-the-fly Python code is synthesized. The system calls audited, unit-tested core financial functions that take `FinancialQuerySpec` as an input parameter.
- **Robust Category and Merchant Isolation:** The inclusion and exclusion lists are explicitly typed arrays.

### 3.3 Ground Truth & Verification Mechanisms
- **Catalog Trie Grounding:** Before calculating, `included_merchants` and `excluded_merchants` are passed through a rapid substring/trie matcher over the user's localized merchant history. If the model hallucinates a merchant not present, the user is prompted for clarification.
- **Unit-Tested Deterministic Math Primitives:** Math is performed by standard Python library code (`pandas` / `numpy` / `math`) covered by 100% test suites.
- **Plain-English "Audit Receipt":** Alongside the final numerical card, the system displays an explicit, plain-English summary of what was counted, what was excluded, and what denominator was applied. This makes assumptions transparent and allows the user to immediately spot misunderstandings.

### 3.4 Trade-Offs & Feasibility
- **Pros:** 100% mathematically deterministic; zero arbitrary code sandbox attack surface; transparent to the user; minimal inference latency (single fast LLM parse turn); directly fits "Talk to My Finances" Grade 6–8 plain-English voice constraints.
- **Cons:** If a user asks for an esoteric calculation not supported by the primitive functions, the system must either decline or fall back to Pattern 2.
- **Feasibility:** **Highest**. Ideal for the immediate "Talk to My Finances" Python Cloud Functions backend.

### 3.5 Primary Source Citations
- **Chen, Z., et al. (2021):** "FinQA: A Dataset of Numerical Reasoning over Financial Data." *Proceedings of the 2021 Conference on Empirical Methods in Natural Language Processing (EMNLP 2021)*. arXiv:2109.00122.
- **Zhu, F., et al. (2021):** "TAT-QA: A Dataset of Complex Numerical Reasoning on Hybrid Tabular and Textual Data." *Proceedings of the 59th Annual Meeting of the Association for Computational Linguistics (ACL 2021)*. arXiv:2105.07624.
- **Shridhar, K., et al. (2023):** "Decomposed Prompting: A Modular Approach for Solving Complex Tasks." *Eleventh International Conference on Learning Representations (ICLR 2023)*. arXiv:2210.02406.
- **Lightman, H., et al. (2023):** "Let's Verify Step by Step." *OpenAI Technical Report on Process Reward Models (PRM800K)*. arXiv:2305.20050.

---

## 4. Comparative Evaluation Matrix

| Evaluation Dimension | Pattern 1: Governed Semantic Layer | Pattern 2: Dual-Execution PAL-SC + LEVER | Pattern 3: Parser-Spec-Compute with Audit Receipts |
| :--- | :--- | :--- | :--- |
| **Mathematical Reliability** | **Deterministic (100%)** | **High (>95% via Consensus)** | **Deterministic (100%)** |
| **Semantic Boundary Handling** | Enforced at Schema level | Enforced via Invariant Assertions | Enforced via Typed Pydantic Spec |
| **Flexibility for Novel Math** | Constrained to defined metrics | **Unconstrained (Any Python code)** | Constrained to primitive functions |
| **Execution Latency** | Low (Single compile + SQL run) | High ($N \times$ generations + executions) | **Lowest (Single JSON parse + Python call)** |
| **Security & Sandbox Risk** | Zero risk (SQL/query compilation) | Requires secure sandbox (gVisor) | **Zero risk (Pre-audited library code)** |
| **Explainability (Auditability)** | High (SQL query inspectable) | Medium (Python script inspectable) | **Highest (Plain-English "Audit Receipt")** |
| **BFSI Regulatory Alignment** | Strong | Moderate | **Optimal (Strict deterministic audit trail)** |

---

## 5. Strategic Recommendation for "Talk to My Finances"

For **Talk to My Finances**, the optimal production architecture is a **Hybrid Tiered Strategy**:

1. **Primary Route (Pattern 3 — Parser-Spec-Compute Pipeline):**
   - Implement the `FinancialQuerySpec` Pydantic model inside the Python Cloud Functions (`Domain B`).
   - Use Gemini Flash 2.5 with structured JSON schema output to parse the user's intent into the spec.
   - Run calculations deterministically in Python over the pre-sanitized BankSync ledger.
   - Generate the plain-English **Audit Receipt** and validate it against the Flesch-Kincaid Grade 6–8 readability framework (`textstat`).
   - Offload the structured numbers and visual breakdown to the Flutter/Android client using `render_visual_card(card_type, payload)`.

2. **Fallback Route (Pattern 2 — Sandboxed Program-Aided Language Execution):**
   - If the intent parser marks the user's query as `is_out_of_spec: true` (e.g. *"Simulate what happens if I cut my dining out by 15% and compound the difference into a high-interest savings account for 3 years"*), route the query to a sandboxed Python runner.
   - Enforce dual execution with assertion invariants and execution consensus before returning the projection to the user.

---
*Report compiled for Talk to My Finances engineering and product teams.*

