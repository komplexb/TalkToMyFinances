from typing import List, Literal, Optional
from pydantic import BaseModel, Field

class FinancialQuerySpec(BaseModel):
    target_metric: Literal["MONTHLY_AVERAGE", "TOTAL_SUM", "TRANSACTION_COUNT", "RUN_RATE"]
    included_merchants: List[str] = Field(default_factory=list)
    excluded_merchants: List[str] = Field(default_factory=list)
    included_categories: List[str] = Field(default_factory=list)
    excluded_categories: List[str] = Field(default_factory=list)
    temporal_grain: Literal["WEEK", "PAY_CYCLE", "MONTH", "QUARTER", "YEAR"] = "MONTH"
    denominator_strategy: Literal["ACTIVE_PERIODS_ONLY", "ALL_CALENDAR_PERIODS"] = "ACTIVE_PERIODS_ONLY"
    exclude_pending: bool = True
    exclude_internal_transfers: bool = True

class AuditReceipt(BaseModel):
    summary: str
    value: Optional[float] = None
    spec: FinancialQuerySpec

class InsightChip(BaseModel):
    text: str
    recency: Literal["this_week", "this_pay_cycle", "last_30_days", "this_quarter", "six_months", "ytd"]
    isSerendipity: bool = False

class AuditResponse(BaseModel):
    chips: List[InsightChip]
