# ADR 0003: Parser-Spec-Compute Architecture for Out-of-Catalog Financial Queries

To prevent mathematical hallucinations and ad-hoc code generation drift on open-ended financial questions (e.g., custom merchant aggregations or exclusions), the system adopts the Parser-Spec-Compute pattern:
1. Gemini acts as an Intent Parser, mapping freeform user questions into a strongly-typed `FinancialQuerySpec` (capturing target metric, entity inclusions/exclusions, temporal grain, and denominator policy).
2. The Python Auditor Engine computes results using pre-audited, unit-tested deterministic math functions over the local transaction ledger.
3. Every response outputs an explicit, plain-English "Audit Receipt" (e.g., stating the active months counted, entities excluded, and final verified amount) alongside the verified numbers.

Arbitrary on-the-fly code generation in client sandboxes is explicitly avoided for financial calculations.

