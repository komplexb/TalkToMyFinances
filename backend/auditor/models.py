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
    """Strongly-typed spec populated by the Intent Parser, executed deterministically by the Auditor Engine.

    `PERIOD_AVERAGE` divides the window total by the denominator bucket (per `temporal_grain`
    and `denominator_strategy`); `RUN_RATE` is the same per-period average annualised.
    """

    target_metric: Literal["PERIOD_AVERAGE", "TOTAL_SUM", "TRANSACTION_COUNT", "RUN_RATE"]
    included_merchants: List[str] = Field(default_factory=list)
    excluded_merchants: List[str] = Field(default_factory=list)
    included_categories: List[str] = Field(default_factory=list)
    excluded_categories: List[str] = Field(default_factory=list)
    date_window: DateWindow = Field(default_factory=DateWindow)
    temporal_grain: Literal["WEEK", "PAY_CYCLE", "MONTH", "QUARTER", "YEAR"] = "MONTH"
    denominator_strategy: Literal["ACTIVE_PERIODS_ONLY", "ALL_CALENDAR_PERIODS"] = "ACTIVE_PERIODS_ONLY"
    exclude_pending: bool = True
    exclude_internal_transfers: bool = True


class AuditReceipt(BaseModel):
    summary: str
    value: Optional[float] = None
    periods_counted: Optional[int] = None
    window: Optional[str] = None
    spec: FinancialQuerySpec


class InsightChip(BaseModel):
    text: str
    recency: Literal["this_week", "this_pay_cycle", "last_30_days", "this_quarter", "six_months", "ytd"]
    isSerendipity: bool = False


class AuditResponse(BaseModel):
    chips: List[InsightChip]
