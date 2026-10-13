import json
import os
from pathlib import Path
from typing import Any, Dict, List, Optional
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from auditor.config import CURATION_MODEL_NAME, PARSER_MODEL_NAME
from auditor.models import AuditReceipt, AuditResponse, FinancialQuerySpec, InsightChip

ROOT = Path(__file__).resolve().parents[1]
TRANSACTIONS_FILE = ROOT / "mock_banksync" / "data" / "transactions.json"

app = FastAPI(title="TalkToMyFinances Auditor & Curation API", version="0.1.0")

class AuditRequest(BaseModel):
    goal: Optional[str] = "SAVINGS"
    payFrequency: Optional[str] = "FORTNIGHTLY"
    nextPayDate: Optional[str] = "2026-10-15"

class QueryRequest(BaseModel):
    query: str

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

def execute_spec_compute(spec: FinancialQuerySpec, transactions: List[Dict[str, Any]]) -> AuditReceipt:
    """Executes deterministic calculation over synthetic ledger using FinancialQuerySpec."""
    filtered = []
    for tx in transactions:
        # Filter pending
        if spec.exclude_pending and tx.get("status") == "PENDING":
            continue
        # Filter internal transfers
        if spec.exclude_internal_transfers and tx.get("isInternalTransfer"):
            continue

        merchant = tx.get("merchantName", "").lower()
        category = tx.get("category", "").lower()

        # Included / excluded merchants
        if spec.included_merchants and not any(m.lower() in merchant for m in spec.included_merchants):
            continue
        if any(m.lower() in merchant for m in spec.excluded_merchants):
            continue

        # Included / excluded categories
        if spec.included_categories and not any(c.lower() in category for c in spec.included_categories):
            continue
        if any(c.lower() in category for c in spec.excluded_categories):
            continue

        filtered.append(tx)

    amounts = [abs(float(tx.get("amount", 0))) for tx in filtered]
    total = sum(amounts)
    count = len(amounts)

    if spec.target_metric == "TOTAL_SUM":
        val = total
        summary = f"Total spend calculated across {count} matching transactions is ${val:.2f}."
    elif spec.target_metric == "TRANSACTION_COUNT":
        val = float(count)
        summary = f"Found {count} matching transactions."
    elif spec.target_metric == "MONTHLY_AVERAGE":
        # Divide by distinct months in matched set or 1 if empty
        months = set(tx.get("date", "")[:7] for tx in filtered if tx.get("date"))
        num_months = len(months) if (spec.denominator_strategy == "ACTIVE_PERIODS_ONLY" and months) else 1
        val = total / max(num_months, 1)
        summary = f"Average monthly spend across {num_months} active month(s) is ${val:.2f} (Total: ${total:.2f})."
    else:  # RUN_RATE
        val = total
        summary = f"Run rate projection calculated at ${val:.2f}."

    return AuditReceipt(summary=summary, value=round(val, 2), spec=spec)

@app.post("/compute", response_model=AuditReceipt)
def compute_spec(spec: FinancialQuerySpec) -> AuditReceipt:
    """Computes deterministic math and Audit Receipt directly from a FinancialQuerySpec."""
    txs = load_transactions()
    return execute_spec_compute(spec, txs)

@app.post("/query", response_model=AuditReceipt)
def run_query(req: QueryRequest) -> AuditReceipt:
    """Parser-Spec-Compute endpoint for out-of-catalog financial math."""
    client = get_genai_client()
    if not client:
        raise HTTPException(
            status_code=500,
            detail="GEMINI_API_KEY or GOOGLE_API_KEY environment variable is not configured on the backend."
        )

    try:
        prompt = (
            f"You are an Intent Parser for financial queries. Parse the following query into a JSON object:\n"
            f"Query: \"{req.query}\"\n\n"
            f"JSON Schema details:\n"
            f"- target_metric: 'MONTHLY_AVERAGE' | 'TOTAL_SUM' | 'TRANSACTION_COUNT' | 'RUN_RATE'\n"
            f"- included_merchants: list of strings (e.g., ['Uber Eats'])\n"
            f"- excluded_merchants: list of strings\n"
            f"- included_categories: list of strings\n"
            f"- excluded_categories: list of strings\n"
            f"- temporal_grain: 'WEEK' | 'PAY_CYCLE' | 'MONTH' | 'QUARTER' | 'YEAR'\n"
            f"- denominator_strategy: 'ACTIVE_PERIODS_ONLY' | 'ALL_CALENDAR_PERIODS'\n"
            f"- exclude_pending: boolean (default true)\n"
            f"- exclude_internal_transfers: boolean (default true)\n"
            f"Return ONLY valid JSON."
        )
        response = client.models.generate_content(
            model=PARSER_MODEL_NAME,
            contents=prompt,
        )
        if not response.text:
            raise HTTPException(status_code=500, detail="Gemini Intent Parser returned empty response.")

        clean_text = response.text.replace("```json", "").replace("```", "").strip()
        spec = FinancialQuerySpec.model_validate_json(clean_text)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Intent Parser execution failed: {str(e)}")

    txs = load_transactions()
    return execute_spec_compute(spec, txs)
