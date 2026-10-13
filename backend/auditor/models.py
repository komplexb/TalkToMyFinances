from typing import List, Literal, Optional
from pydantic import BaseModel, Field


class DateWindow(BaseModel):
    """Temporal window for the calculation, resolved by the engine relative to the ledger's as-of date.

    CUSTOM requires at least `start`; `end` defaults to the ledger as-of date.
    """

    type: Literal[
        "LAST_MONTH",
        "LAST_30_DAYS",
        "THIS_QUARTER",
        "SIX_MONTHS",
        "YTD",
        "ALL_TIME",
        "CUSTOM",
    ] = "ALL_TIME"
    start: Optional[str] = None  # YYYY-MM-DD (CUSTOM only)
    end: Optional[str] = None  # YYYY-MM-DD (CUSTOM only)


class FinancialQuerySpec(BaseModel):
    """Strongly-typed spec populated by the Intent Parser, executed deterministically by the Auditor Engine (ADR 0003).

    Metrics:
    - AVERAGE_TRANSACTION: window total / transaction count (e.g. "average order amount").
    - PERIOD_AVERAGE: window total / denominator bucket (per `temporal_grain` and
      `denominator_strategy`).
    - TRANSACTION_COUNT: transactions / denominator bucket ("how often … per week").
    - TOTAL_SUM, RUN_RATE (per-period average annualised).

    Fixed invariants (deliberately NOT model choices):
    - Credits — income and refunds/reversals — never count as spend. Refunds are
      excluded outright in the prototype rather than netted against their original
      transaction (netting requires linking each refund to its original; deferred).
      The Audit Receipt always states this.
    - Pending transactions and internal transfers are excluded while the
      `exclude_pending` / `exclude_internal_transfers` flags are set (both default true).

    `is_out_of_spec` is the escape hatch: the parser sets it for queries no audited
    primitive can answer (synthesis like "describe my habits", or "what if"
    simulations) so the engine never force-fits a number (gap 8).
    """

    target_metric: Literal["AVERAGE_TRANSACTION", "PERIOD_AVERAGE", "TOTAL_SUM", "TRANSACTION_COUNT", "RUN_RATE"]
    included_merchants: List[str] = Field(default_factory=list)
    excluded_merchants: List[str] = Field(default_factory=list)
    included_categories: List[str] = Field(default_factory=list)
    excluded_categories: List[str] = Field(default_factory=list)
    date_window: DateWindow = Field(default_factory=DateWindow)
    temporal_grain: Literal["WEEK", "PAY_CYCLE", "MONTH", "QUARTER", "YEAR"] = "MONTH"
    denominator_strategy: Literal["ACTIVE_PERIODS_ONLY", "ALL_CALENDAR_PERIODS"] = "ACTIVE_PERIODS_ONLY"
    exclude_pending: bool = True
    exclude_internal_transfers: bool = True
    is_out_of_spec: bool = False
    out_of_spec_reason: Optional[str] = None


class AuditReceipt(BaseModel):
    """The answer plus a plain-English record of the assumptions made.

    `summary` states what was counted (matched entities), what was excluded, how
    pending/refunds were handled, and which denominator was used — so the user can
    spot a misunderstanding at a glance (ADR 0003 audit receipt). The `matched_*`
    lists carry the ledger's canonical names; `unmatched_terms` lists requested
    terms that resolved to nothing in the ledger.
    """

    summary: str
    value: Optional[float] = None
    periods_counted: Optional[int] = None
    window: Optional[str] = None
    matched_merchants: List[str] = Field(default_factory=list)
    matched_excluded_merchants: List[str] = Field(default_factory=list)
    matched_categories: List[str] = Field(default_factory=list)
    unmatched_terms: List[str] = Field(default_factory=list)
    spec: FinancialQuerySpec


class InsightChip(BaseModel):
    text: str
    recency: Literal["this_week", "this_pay_cycle", "last_30_days", "this_quarter", "six_months", "ytd"]
    isSerendipity: bool = False


class AuditResponse(BaseModel):
    chips: List[InsightChip]
