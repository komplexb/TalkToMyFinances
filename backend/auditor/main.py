import json
import os
import re
from dataclasses import dataclass
from datetime import date, timedelta
from difflib import get_close_matches
from pathlib import Path
from typing import Any, Dict, List, Optional
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from auditor.config import CURATION_MODEL_NAME, PARSER_MODEL_NAME
from auditor.models import AuditReceipt, AuditResponse, FinancialQuerySpec, InsightChip
from auditor.prompt_templates import render_prompt

# prompts/intent-parser-template-v1-0-0.prompt — the Intent Parser's system prompt
# lives in a template file, not in this module.
INTENT_PARSER_TEMPLATE = "intent-parser-template-v1-0-0"
# prompts/curation-template-v1-0-0.prompt — same for the Curation Agent.
CURATION_TEMPLATE = "curation-template-v1-0-0"

ROOT = Path(__file__).resolve().parents[1]
load_dotenv(ROOT / ".env")
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
    {"id": 1, "text": "Nice work on cafes this week — you're only at $9.50, so you've definitely got room for a cheeky brunch!", "recency": "this_week", "category": "dining", "is_serendipity": False, "drilldown_prompt": "How much did I spend on cafes this week?"},
    {"id": 2, "text": "Zero takeaway spend since Tuesday! You've got plenty of room to celebrate Friday night with pizza or takeout.", "recency": "this_pay_cycle", "category": "dining", "is_serendipity": False, "drilldown_prompt": "How much did I spend on takeaway this pay cycle?"},
    {"id": 3, "text": "Your utility bills came in $140 lower this quarter ($105.83 vs $245.82). Want to see where you saved?", "recency": "this_quarter", "category": "utilities", "is_serendipity": True, "drilldown_prompt": "How much did I spend on utility bills this quarter?"},
    {"id": 4, "text": "Groceries were 12% lower over the last 30 days.", "recency": "last_30_days", "category": "groceries", "is_serendipity": False, "drilldown_prompt": "How much did I spend on groceries in the last 30 days?"},
    {"id": 5, "text": "Subscription costs increased by $15/mo over 6 months.", "recency": "six_months", "category": "subscriptions", "is_serendipity": False, "drilldown_prompt": "How much did I spend on subscriptions over the last 6 months?"},
    {"id": 6, "text": "Net savings buffer grew 8% YTD.", "recency": "ytd", "category": "savings", "is_serendipity": False, "drilldown_prompt": "What is my net savings buffer so far this year?"},
    {"id": 7, "text": "Weekend shopping sprees are down 20% this pay cycle.", "recency": "this_pay_cycle", "category": "shopping", "is_serendipity": False, "drilldown_prompt": "How much did I spend on shopping this pay cycle?"},
    {"id": 8, "text": "Transport costs dipped $45 this week.", "recency": "this_week", "category": "transport", "is_serendipity": False, "drilldown_prompt": "How much did I spend on transport this week?"},
    {"id": 9, "text": "Cash flow margin is healthy heading into your next pay date.", "recency": "this_pay_cycle", "category": "savings", "is_serendipity": False, "drilldown_prompt": "What is my cash flow margin this pay cycle?"},
    {"id": 10, "text": "Entertainment spend is on track for the quarter.", "recency": "this_quarter", "category": "entertainment", "is_serendipity": False, "drilldown_prompt": "How much did I spend on entertainment this quarter?"},
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
        prompt = render_prompt(
            CURATION_TEMPLATE,
            {
                "goal": req.goal,
                "catalog": json.dumps(FACT_CATALOG),
            },
        )
        response = client.models.generate_content(
            model=CURATION_MODEL_NAME,
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
    # Deterministic invariant: only debits count as spend. Credits — income AND
    # refunds/reversals — never do. Refunds are excluded outright rather than
    # netted against their original transaction (netting is a tracked follow-up);
    # the Audit Receipt always states this.
    return str(tx.get("type", "")).lower() == "debit" or float(tx.get("amount", 0)) < 0


def canonical_merchant(tx: Dict[str, Any]) -> str:
    """The ledger's canonical name for a transaction's counterparty."""
    return str(tx.get("merchantName") or tx.get("description") or "").strip()


def _normalize(value: str) -> str:
    """Lowercase and collapse punctuation/whitespace so 'Uber Eats' matches 'UBER *EATS'."""
    return re.sub(r"[^a-z0-9]+", " ", value.lower()).strip()


@dataclass(frozen=True)
class LedgerVocabulary:
    """The distinct merchant names and categories actually present in the ledger."""

    merchants: List[str]
    categories: List[str]


def build_vocabulary(transactions: List[Dict[str, Any]]) -> LedgerVocabulary:
    merchants = sorted({canonical_merchant(t) for t in transactions if canonical_merchant(t)})
    categories = sorted({str(t.get("category", "")).strip() for t in transactions if t.get("category")})
    return LedgerVocabulary(merchants=merchants, categories=categories)


def ground_terms(terms: List[str], vocabulary: List[str]) -> tuple[List[str], List[str]]:
    """Ground user-supplied terms against the ledger's actual vocabulary.

    Case- and punctuation-insensitive substring match (so "uber" resolves to both
    UBER and UBER *EATS — the receipt echoes exactly which names matched).
    Returns (matched canonical names, unmatched terms).
    """
    matched: List[str] = []
    unmatched: List[str] = []
    for term in terms:
        needle = _normalize(term)
        hits = [v for v in vocabulary if needle in _normalize(v)]
        if hits:
            matched.extend(hits)
        else:
            unmatched.append(term)
    return sorted(set(matched)), list(dict.fromkeys(unmatched))


def _resolve_pay_cycle_days(pay_frequency: str) -> int:
    return 14 if pay_frequency.upper().startswith("FORT") else 7


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
    cycle_days = _resolve_pay_cycle_days(pay_frequency)
    base = _parse_date(next_pay_date) if next_pay_date else anchor
    return f"C{(d - base).days // cycle_days}"


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
    elif w.type == "THIS_WEEK":
        # ISO Monday through as-of date (anchor)
        start = anchor - timedelta(days=anchor.weekday())
        end = anchor
    elif w.type == "LAST_WEEK":
        # Prior full Monday through Sunday
        this_monday = anchor - timedelta(days=anchor.weekday())
        start = this_monday - timedelta(days=7)
        end = this_monday - timedelta(days=1)
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
    """Executes a FinancialQuerySpec deterministically over the ledger.

    Pure function of (spec, ledger) — no LLM in this path.
    """
    if spec.is_out_of_spec:
        # Guard invariant: no code path ever computes a number from an out-of-spec spec.
        raise ValueError("This question is beyond what I can verify with the audited math primitives.")

    dated = [(_parse_date(tx["date"]), tx) for tx in transactions if tx.get("date")]
    if not dated:
        raise ValueError("Ledger contains no dated transactions.")
    ledger_min = min(d for d, _ in dated)
    ledger_max = max(d for d, _ in dated)
    anchor = ledger_max

    win_start, win_end = resolve_window(spec, anchor, ledger_min, ledger_max)

    # Ground requested entities against the ledger's actual vocabulary (gap 4):
    # matches are resolved to canonical names and echoed in the receipt.
    vocab = build_vocabulary(transactions)
    inc_merchants, unmatched_inc_m = ground_terms(spec.included_merchants, vocab.merchants)
    excl_merchants, unmatched_excl_m = ground_terms(spec.excluded_merchants, vocab.merchants)
    inc_categories, unmatched_inc_c = ground_terms(spec.included_categories, vocab.categories)
    excl_categories, unmatched_excl_c = ground_terms(spec.excluded_categories, vocab.categories)
    unmatched_terms = unmatched_inc_m + unmatched_excl_m + unmatched_inc_c + unmatched_excl_c

    inc_m_set = {_normalize(m) for m in inc_merchants}
    excl_m_set = {_normalize(m) for m in excl_merchants}
    inc_c_set = {_normalize(c) for c in inc_categories}
    excl_c_set = {_normalize(c) for c in excl_categories}

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
        name = _normalize(canonical_merchant(tx))
        # Key off the spec (not the derived set) so an unmatched inclusion
        # counts NOTHING rather than silently everything.
        if spec.included_merchants and name not in inc_m_set:
            continue
        if name in excl_m_set:
            continue
        category = _normalize(str(tx.get("category", "")))
        if spec.included_categories and category not in inc_c_set:
            continue
        if category in excl_c_set:
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
    elif spec.target_metric == "AVERAGE_TRANSACTION":
        val = total / count if count else 0.0
        core = f"your average transaction was ${val:,.2f} ({count} transaction{'s' if count != 1 else ''}, ${total:,.2f} total)"
    elif spec.target_metric == "PERIOD_AVERAGE":
        val = per
        core = f"you averaged ${per:,.2f} per {label} (${total:,.2f} total)"
    else:  # RUN_RATE
        val = per * PERIODS_PER_YEAR[grain]
        core = f"your run rate is ${val:,.2f} per year (${per:,.2f} per {label})"

    # Audit receipt content: what was counted, what was excluded, and how.
    counted_parts: List[str] = []
    if spec.included_merchants:
        counted_parts.append("merchants: " + (", ".join(inc_merchants) if inc_merchants else "no match found"))
    if spec.included_categories:
        counted_parts.append("categories: " + (", ".join(inc_categories) if inc_categories else "no match found"))
    if not counted_parts:
        counted_parts.append("all merchants")

    exclusions: List[str] = []
    if spec.exclude_pending:
        exclusions.append("pending transactions")
    if spec.exclude_internal_transfers:
        exclusions.append("internal transfers")
    exclusions.append("credits, income and refunds")
    if spec.excluded_merchants:
        exclusions.append(
            "merchants: "
            + (
                ", ".join(excl_merchants)
                if excl_merchants
                else ", ".join(f"'{t}' (no match)" for t in spec.excluded_merchants)
            )
        )
    if spec.excluded_categories:
        exclusions.append(
            "categories: "
            + (
                ", ".join(excl_categories)
                if excl_categories
                else ", ".join(f"'{t}' (no match)" for t in spec.excluded_categories)
            )
        )

    window_desc = f"{win_start} to {win_end} ({spec.date_window.type})"
    summary = (
        f"{core.capitalize()}. Window: {window_desc}. "
        f"Denominator: {denom_desc} ({spec.denominator_strategy}). "
        f"Counted: {'; '.join(counted_parts)}. Excluded: {'; '.join(exclusions)}."
    )

    return AuditReceipt(
        summary=summary,
        value=round(val, 2),
        periods_counted=denom,
        window=window_desc,
        matched_merchants=inc_merchants,
        matched_excluded_merchants=excl_merchants,
        matched_categories=inc_categories,
        unmatched_terms=unmatched_terms,
        spec=spec,
    )


def _parse_query_with_llm(query: str, transactions: List[Dict[str, Any]]) -> Optional[FinancialQuerySpec]:
    """Intent Parser: maps a freeform query to a FinancialQuerySpec (ADR 0003 phase 1).

    Uses structured output — the Pydantic schema is the contract, no fence-stripping.
    Returns None on any failure; /query turns that into a clarification, never a guess.
    """
    client = get_genai_client()
    if client is None:
        return None

    vocab = build_vocabulary(transactions)
    try:
        prompt = render_prompt(
            INTENT_PARSER_TEMPLATE,
            {
                "query": query,
                "merchants": json.dumps(vocab.merchants),
                "categories": json.dumps(vocab.categories),
            },
        )
    except Exception:
        # Missing/malformed template is a parse failure, not a crash:
        # /query turns None into a clarification, never a 500.
        return None
    try:
        from google.genai import types

        response = client.models.generate_content(
            model=PARSER_MODEL_NAME,
            contents=prompt,
            config=types.GenerateContentConfig(
                response_mime_type="application/json",
                response_schema=FinancialQuerySpec,
            ),
        )
        if response and response.text:
            return FinancialQuerySpec.model_validate_json(response.text)
    except Exception:
        return None
    return None


def _typed_error(status_code: int, status: str, message: str) -> JSONResponse:
    return JSONResponse(status_code=status_code, content={"status": status, "message": message})


def _closest_matches(term: str, vocabulary: List[str], n: int = 3) -> List[str]:
    pool = {v.lower(): v for v in vocabulary}
    return [pool[m] for m in get_close_matches(term.lower(), list(pool), n=n, cutoff=0.6)]


@app.post("/compute", response_model=AuditReceipt)
def compute_spec(spec: FinancialQuerySpec) -> Response:
    """Deterministic compute endpoint (ADR 0003): spec in, Audit Receipt out.

    No LLM in this path. Engine rejections surface as typed 422s, never 500s.
    """
    transactions = load_transactions()
    try:
        return execute_spec_compute(spec, transactions)
    except ValueError as e:
        return _typed_error(422, "uncomputable", str(e))


@app.post("/query")
def run_query(req: QueryRequest) -> Response:
    """Parser-Spec-Compute endpoint for out-of-catalog financial math (ADR 0003).

    The only non-deterministic step is the LLM parse (when a raw `query` is given).
    Given a spec, the engine is pure and unit-tested: the LLM never does arithmetic.
    """
    transactions = load_transactions()
    vocab = build_vocabulary(transactions)

    spec = req.spec
    if spec is None:
        if not req.query:
            return _typed_error(422, "unparseable", "Give me a question about your spending, or a parsed spec.")
        spec = _parse_query_with_llm(req.query, transactions)
    if spec is None:
        # Never guess: a fallback spec would produce a confident, unverifiable number.
        return _typed_error(
            422,
            "unparseable",
            "I couldn't work out exactly what to calculate — could you rephrase that?",
        )
    if spec.is_out_of_spec:
        # Synthesis/advice/simulation queries don't map to any audited primitive:
        # route them on (the Translation Agent narrates) instead of forcing a number.
        return JSONResponse(
            {
                "status": "out_of_spec",
                "reason": spec.out_of_spec_reason or "That's beyond what I can verify from your transactions.",
            }
        )

    # Ground the requested *inclusions* against the ledger: a hallucinated entity
    # surfaces as a clarification, never a confident zero (gap 4).
    unmatched = ground_terms(spec.included_merchants, vocab.merchants)[1] + ground_terms(
        spec.included_categories, vocab.categories
    )[1]
    if unmatched:
        suggestions = sorted(
            {s for t in unmatched for s in _closest_matches(t, vocab.merchants + vocab.categories)}
        )
        message = f"I couldn't find {', '.join(repr(t) for t in unmatched)} in your transactions."
        if suggestions:
            message += f" Did you mean: {', '.join(suggestions)}?"
        return _typed_error(422, "entity_not_found", message)

    try:
        return execute_spec_compute(
            spec, transactions, req.pay_frequency or "FORTNIGHTLY", req.next_pay_date
        )
    except ValueError as e:
        return _typed_error(422, "uncomputable", str(e))
