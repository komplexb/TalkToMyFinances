"""A fixed, read-only transaction endpoint for local development."""

import base64
import binascii
import json
import os
import re
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

from dotenv import load_dotenv
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

ROOT = Path(__file__).resolve().parents[1]
load_dotenv(ROOT / ".env")
# No fallback: if the key is unset, every request is rejected (fail closed).
MOCK_KEY = os.getenv("BANKSYNC_MOCK_KEY")
PAGE_SIZE = 25
TRANSACTIONS = json.loads(
    (ROOT / "mock_banksync/data/transactions.json").read_text(encoding="utf-8")
)

app = FastAPI(title="Local BankSync Mock", version="0.1.0")


@app.exception_handler(HTTPException)
async def http_error(request: Request, exc: HTTPException):
    return JSONResponse(
        status_code=exc.status_code,
        content={"success": False, "error": str(exc.detail)},
        headers=exc.headers,
    )


@app.exception_handler(RequestValidationError)
async def validation_error(request: Request, exc: RequestValidationError):
    return JSONResponse(
        status_code=400,
        content={"success": False, "error": "Invalid request parameters"},
    )


def require_key(x_api_key: str | None = Header(default=None)):
    if MOCK_KEY is None or x_api_key != MOCK_KEY:
        raise HTTPException(status_code=401, detail="Missing or invalid API key")


def parse_date(value: str) -> date:
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
        raise HTTPException(status_code=400, detail="Use dates in YYYY-MM-DD format")
    try:
        return date.fromisoformat(value)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid calendar date") from None


def encode_cursor(offset: int, start: str, end: str) -> str:
    # Keep the date range in the cursor so pages cannot mix different queries.
    payload = json.dumps([offset, start, end]).encode()
    return base64.urlsafe_b64encode(payload).decode()


def decode_cursor(cursor: str, start: str, end: str, count: int) -> int:
    try:
        if len(cursor) > 512:
            raise ValueError
        payload = base64.b64decode(cursor, altchars=b"-_", validate=True)
        offset, saved_start, saved_end = json.loads(payload)
        if (
            type(offset) is not int
            or offset <= 0
            or offset >= count
            or offset % PAGE_SIZE != 0
            or saved_start != start
            or saved_end != end
        ):
            raise ValueError
        return offset
    except (ValueError, TypeError, binascii.Error, UnicodeError):
        raise HTTPException(
            status_code=400,
            detail="Invalid cursor; keep the same from/to dates when paging",
        ) from None


@app.get(
    "/v1/banks/{bid}/accounts/{aid}/transactions",
    dependencies=[Depends(require_key)],
)
def list_transactions(
    bid: str,
    aid: str,
    from_date: str | None = Query(default=None, alias="from"),
    to_date: str | None = Query(default=None, alias="to"),
    cursor: str | None = None,
):
    if bid != "demo" or aid != "spending":
        raise HTTPException(status_code=404, detail="Bank or account not found")

    end = parse_date(to_date) if to_date is not None else datetime.now(timezone.utc).date()
    start = parse_date(from_date) if from_date is not None else end - timedelta(days=30)
    if start > end:
        raise HTTPException(status_code=400, detail="from must be on or before to")

    start_text, end_text = start.isoformat(), end.isoformat()
    rows = sorted(
        (row for row in TRANSACTIONS if start_text <= row["date"] <= end_text),
        key=lambda row: (row["date"], row["id"]),
    )
    offset = decode_cursor(cursor, start_text, end_text, len(rows)) if cursor is not None else 0
    page = rows[offset : offset + PAGE_SIZE]
    next_offset = offset + len(page)
    has_more = next_offset < len(rows)
    return {
        "success": True,
        "data": page,
        "meta": {
            "count": len(page),
            "cursor": encode_cursor(next_offset, start_text, end_text) if has_more else None,
            "hasMore": has_more,
            "from": start_text,
            "to": end_text,
        },
    }
