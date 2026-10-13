import json
import os
from datetime import date, timedelta
from pathlib import Path
from typing import Any, Dict, List, Optional
from fastapi import FastAPI, HTTPException, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from auditor.models import AuditReceipt, AuditResponse, DateWindow, FinancialQuerySpec, InsightChip

ROOT = Path(__file__).resolve().parents[1]
TRANSACTIONS_FILE = ROOT / "mock_banksync" / "data" / "transactions.json"

app = FastAPI(title="TalkToMyFinances Auditor & Curation API", version="0.1.0")

class AuditRequest(BaseModel):
    goal: Optional[str] = "SAVINGS"
    payFrequency: Optional[str] = "FORTNIGHTLY"
    nextPayDate: Optional[str] = "2026-10-15"

class QueryRequest(BaseModel):
    query: Optional[str] = None
    spec: Optional[FinancialQuerySpec] = None  # pre-parsed spec (e.g. from client-side LLM)
    pay_frequency: Optional[str] = "FORTNIGHTLY"
    next_pay_date: Optional[str] = None  # YYYY-MM-DD, anchors PAY_CYCLE buckets

# Hardcoded 10-Fact Deterministic Anomaly Catalog for Prototype (PRD 5.2)
FACT_CATALOG = [
    {"id": 1, "text": "Nice work on your cafe spending this week. You have room for a cheeky brunch!", "recency": "this_week", "category": "dining", "is_serendipity": False},
    {"id": 2, "text": "You have $35 ready if you want Friday night pizza.", "recency": "this_pay_cycle", "category": "dining", "is_serendipity": False},
    {"id": 3, "text": "Your utility bills came in $140 lower this quarter. Want to see why?", "recency": "this_quarter", "category": "utilities", "is_serendipity": True},
    {"id": 4, "text": "Groceries were 12% lower over the last 30 days.", "recency": "last_30_days", "category": "groceries", "is_serendipity": False},
    {"id": 5, "text": "Subscription costs increased by $15/mo over 6 months.", "recency": "six_months", "category": "subscriptions", "is_serendipity": False},
    {"id": 6, "text": "Net savings buffer grew 8% YTD.", "recency": "ytd", "category": "savings", "is_serendipity": False},
    {"id": 7, "text": "Weekend shopping sprees are down 20% this pay cycle.", "recency": "this_pay_cycle", "category": "shopping", "is_serendipity": False},
    {"id": 8, "text": "Transport costs dipped $45 this week.", "recency": "this_week", "category": "transport", "is_serendipity": False},
    {"id": 9, "text": "Cash flow margin is healthy heading into your next pay date.", "recency": "this_pay_cycle", "category": "savings", "is_serendipity": False},
    {"id": 10, "text": "Entertainment spend is on track for the quarter.", "recency": "this_quarter", "category": "entertainment", "is_serendipity": False},
]

def get_genai_client():
    api_key = os.getenv("GEMINI_API_KEY") or os.getenv("GOOGLE_API_KEY")
    if not api_key:
        return None
    try:
        from google import genai
        return genai.Client(api_key=api_key)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to initialize google-genai client: {str(e)}")

@app.post("/audit", response_model=AuditResponse)
def run_audit(req: AuditRequest) -> AuditResponse:
    """Runs Curation Agent over the Fact Catalog to pick 3 relevant chips (2 goal + 1 serendipity)."""
    client = get_genai_client()
    if not client:
        raise HTTPException(
            status_code=500,
            detail="GEMINI_API_KEY or GOOGLE_API_KEY environment variable is not configured on the backend."
        )

    try:
        prompt = (
            f"Given the user's financial goal '{req.goal}', select 3 most relevant insight chips from this catalog:\n"
            f"{json.dumps(FACT_CATALOG)}\n\n"
            f"Select exactly 2 chips matching or helping their goal '{req.goal}' and 1 serendipity chip (is_serendipity=true).\n"
            f"Return as a JSON array of objects with keys 'text', 'recency', 'isSerendipity'."
        )
        response = client.models.generate_content(
            model=PARSER_MODEL_NAME,
            contents=prompt,
        )
        if not response.text:
            raise HTTPException(status_code=500, detail="Gemini Curation Agent returned empty response.")

        clean_text = response.text.replace("```json", "").replace("```", "").strip()
        items = json.loads(clean_text)
        chips = [InsightChip(**item) for item in items]
        if len(chips) != 3:
            raise HTTPException(
                status_code=500,
                detail=f"Curation Agent returned {len(chips)} chips instead of the expected 3 chips (2 goal + 1 serendipity)."
            )
        return AuditResponse(chips=chips)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Curation Agent execution failed: {str(e)}")

def load_transactions() -> List[Dict[str, Any]]:
    if not TRANSACTIONS_FILE.exists():
        return []
    with open(TRANSACTIONS_FILE, "r", encoding="utf-8") as f:
        return json.load(f)

# ---------------------------------------------------------------------------
# Deterministic compute engine (ADR 0003, Parser-Spec-Compute)
# ---------------------------------------------------------------------------

PERIODS_PER_YEAR = {"WEEK": 52, "PAY_CYCLE": 26, "MONTH": 12, "QUARTER": 4, "YEAR": 1}
GRAIN_LABEL = {"WEEK": "week", "PAY_CYCLE": "pay cycle", "MONTH": "month", "QUARTER": "quarter", "YEAR": "year"}


def _parse_date(value: Any) -> date:
    return date.fromisoformat(str(value)[:10])


def _is_pending(tx: Dict[str, Any]) -> bool:
    return bool(tx.get("pending")) or str(tx.get("status", "")).upper() == "PENDING"


def _is_internal_transfer(tx: Dict[str, Any]) -> bool:
    return bool(tx.get("isInternalTransfer")) or str(tx.get("category", "")).lower() == "transfer"


def _is_spend(tx: Dict[str, Any]) -> bool:
    # Deterministic invariant: only outgoing money counts as spend.
    # (Refund netting is a separate tracked gap; credits are excluded here.)
    return str(tx.get("type", "")).lower() == "debit" or float(tx.get("amount", 0)) < 0


def resolve_window(
    spec: FinancialQuerySpec,
    anchor: date,
    ledger_min: date,
    ledger_max: date,
) -> tuple[date, date]:
    w = spec.date_window
    if w.type == "CUSTOM" and w.start:
        start = _parse_date(w.start)
        end = _parse_date(w.end) if w.end else anchor
    elif w.type == "LAST_MONTH":
        end = anchor.replace(day=1) - timedelta(days=1)
        start = end.replace(day=1)
    elif w.type == "LAST_30_DAYS":
        start, end = anchor - timedelta(days=30), anchor
    elif w.type == "THIS_QUARTER":
        start = date(anchor.year, 3 * ((anchor.month - 1) // 3) + 1, 1)
        end = anchor
    elif w.type == "SIX_MONTHS":
        year, month = anchor.year, anchor.month - 5
        while month <= 0:
            month += 12
            year -= 1
        start, end = date(year, month, 1), anchor
    elif w.type == "YTD":
        start, end = date(anchor.year, 1, 1), anchor
    else:  # ALL_TIME
        start, end = ledger_min, ledger_max
    return start, end


def _bucket_key(
    d: date,
    grain: str,
    anchor: date,
    pay_frequency: str,
    next_pay_date: Optional[str],
) -> str:
    if grain == "WEEK":
        iso = d.isocalendar()
        return f"{iso[0]}-W{iso[1]:02d}"
    if grain == "MONTH":
        return f"{d.year}-{d.month:02d}"
    if grain == "QUARTER":
        return f"{d.year}-Q{(d.month - 1) // 3 + 1}"
    if grain == "YEAR":
        return str(d.year)
    # PAY_CYCLE: 14-day (fortnightly) or 7-day (weekly) buckets aligned to the pay date
    cycle_days = 14 if (pay_frequency or "FORTNIGHTLY").upper().startswith("FORT") else 7
    base = _parse_date(next_pay_date) if next_pay_date else anchor
    return f"C{(d - base).days // cycle_days}"


def _calendar_periods(
    start: date,
    end: date,
    grain: str,
    anchor: date,
    pay_frequency: str,
    next_pay_date: Optional[str],
) -> int:
    """Count of whole calendar periods spanned by the window (inclusive)."""
    if end < start:
        return 1
    keys = set()
    d = start
    while d <= end:
        keys.add(_bucket_key(d, grain, anchor, pay_frequency, next_pay_date))
        d += timedelta(days=1)
    return max(len(keys), 1)


def execute_spec_compute(
    spec: FinancialQuerySpec,
    transactions: List[Dict[str, Any]],
    pay_frequency: str = "FORTNIGHTLY",
    next_pay_date: Optional[str] = None,
) -> AuditReceipt:
    """Executes a FinancialQuerySpec deterministically over the ledger."""
    dated = [(_parse_date(tx["date"]), tx) for tx in transactions if tx.get("date")]
    if not dated:
        raise ValueError("Ledger contains no dated transactions.")
    ledger_min = min(d for d, _ in dated)
    ledger_max = max(d for d, _ in dated)
    anchor = ledger_max

    win_start, win_end = resolve_window(spec, anchor, ledger_min, ledger_max)

    filtered: List[tuple[date, Dict[str, Any]]] = []
    for d, tx in dated:
        if not (win_start <= d <= win_end):
            continue
        if spec.exclude_pending and _is_pending(tx):
            continue
        if spec.exclude_internal_transfers and _is_internal_transfer(tx):
            continue
        if not _is_spend(tx):
            continue
        merchant = str(tx.get("merchantName") or tx.get("description", "")).lower()
        category = str(tx.get("category", "")).lower()
        if spec.included_merchants and not any(m.lower() in merchant for m in spec.included_merchants):
            continue
        if any(m.lower() in merchant for m in spec.excluded_merchants):
            continue
        if spec.included_categories and not any(c.lower() in category for c in spec.included_categories):
            continue
        if any(c.lower() in category for c in spec.excluded_categories):
            continue
        filtered.append((d, tx))

    amounts = [abs(float(tx.get("amount", 0))) for _, tx in filtered]
    total = sum(amounts)
    count = len(amounts)
    grain = spec.temporal_grain
    label = GRAIN_LABEL[grain]

    active = len({_bucket_key(d, grain, anchor, pay_frequency, next_pay_date) for d, _ in filtered})
    if spec.denominator_strategy == "ACTIVE_PERIODS_ONLY":
        denom = max(active, 1)
        denom_desc = f"{max(active, 0)} active {label}{'s' if active != 1 else ''} with spend"
    else:
        denom = _calendar_periods(win_start, win_end, grain, anchor, pay_frequency, next_pay_date)
        denom_desc = f"{denom} calendar {label}{'s' if denom != 1 else ''} in the window"

    per = total / denom
    if spec.target_metric == "TOTAL_SUM":
        val = total
        core = f"you spent ${total:,.2f} across {count} transaction{'s' if count != 1 else ''}"
    elif spec.target_metric == "TRANSACTION_COUNT":
        val = count / denom
        core = f"on average {val:.1f} per {label} ({count} total)"
    elif spec.target_metric == "PERIOD_AVERAGE":
        val = per
        core = f"you averaged ${per:,.2f} per {label} (${total:,.2f} total)"
    else:  # RUN_RATE
        val = per * PERIODS_PER_YEAR[grain]
        core = f"your run rate is ${val:,.2f} per year (${per:,.2f} per {label})"

    exclusions = []
    if spec.exclude_pending:
        exclusions.append("pending")
    if spec.exclude_internal_transfers:
        exclusions.append("internal transfers")
    exclusions.append("credits/refunds")
    if spec.excluded_merchants:
        exclusions.append("merchants: " + ", ".join(spec.excluded_merchants))
    if spec.excluded_categories:
        exclusions.append("categories: " + ", ".join(spec.excluded_categories))

    window_desc = f"{win_start} to {win_end} ({spec.date_window.type})"
    summary = (
        f"{core.capitalize()}. Window: {window_desc}. "
        f"Denominator: {denom_desc} ({spec.denominator_strategy}). Excluded: {'; '.join(exclusions)}."
    )

    return AuditReceipt(
        summary=summary,
        value=round(val, 2),
        periods_counted=denom,
        window=window_desc,
        spec=spec,
    )


def _parse_query_with_llm(query: str) -> Optional[FinancialQuerySpec]:
    """Intent Parser: maps a freeform query to a FinancialQuerySpec. Returns None on any failure."""
    client = get_genai_client()
    if not client:
        return None
    try:
        prompt = (
            f"You are an Intent Parser for financial queries. Parse the following query into a JSON object:\n"
            f"Query: \"{query}\"\n\n"
            f"JSON Schema details:\n"
            f"- target_metric: 'PERIOD_AVERAGE' | 'TOTAL_SUM' | 'TRANSACTION_COUNT' | 'RUN_RATE' "
            f"(TRANSACTION_COUNT for 'how often / how many times'; PERIOD_AVERAGE for 'average spend')\n"
            f"- included_merchants: list of strings (e.g., ['Uber Eats'])\n"
            f"- excluded_merchants: list of strings\n"
            f"- included_categories: list of strings\n"
            f"- excluded_categories: list of strings\n"
            f"- date_window: object with 'type': 'LAST_MONTH' | 'LAST_30_DAYS' | 'THIS_QUARTER' | "
            f"'SIX_MONTHS' | 'YTD' | 'ALL_TIME' | 'CUSTOM', plus 'start'/'end' (YYYY-MM-DD) for CUSTOM. "
            f"Default to 'ALL_TIME' unless the query names a period.\n"
            f"- temporal_grain: 'WEEK' | 'PAY_CYCLE' | 'MONTH' | 'QUARTER' | 'YEAR'\n"
            f"- denominator_strategy: 'ACTIVE_PERIODS_ONLY' | 'ALL_CALENDAR_PERIODS' (default ACTIVE_PERIODS_ONLY)\n"
            f"- exclude_pending: boolean (default true)\n"
            f"- exclude_internal_transfers: boolean (default true)\n"
            f"Return ONLY valid JSON."
        )
        response = client.models.generate_content(model="gemini-2.5-flash", contents=prompt)
        if response and response.text:
            clean_text = response.text.replace("```json", "").replace("```", "").strip()
            return FinancialQuerySpec.model_validate_json(clean_text)
    except Exception:
        return None
    return None


@app.post("/query")
def run_query(req: QueryRequest) -> Response:
    """Parser-Spec-Compute endpoint for out-of-catalog financial math."""
    spec = req.spec
    if spec is None:
        if not req.query:
            return JSONResponse(
                status_code=422,
                content={"status": "unparseable", "message": "Provide either a natural-language query or a parsed FinancialQuerySpec."},
            )
        spec = _parse_query_with_llm(req.query)
    if spec is None:
        # Never guess: a fallback spec would produce a confident, unverifiable number.
        return JSONResponse(
            status_code=422,
            content={"status": "unparseable", "message": "I couldn't work out exactly what to calculate — could you rephrase that?"},
        )
    try:
        txs = load_transactions()
        return execute_spec_compute(spec, txs, req.pay_frequency or "FORTNIGHTLY", req.next_pay_date)
    except ValueError as e:
        return JSONResponse(status_code=422, content={"status": "uncomputable", "message": str(e)})
