import copy
import json
from datetime import date, timedelta
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator

from banksync.banksync_client import BankSyncClient
from mock_banksync.main import MOCK_KEY, TRANSACTIONS, app

PATH = "/v1/banks/demo/accounts/spending/transactions"
ROOT = Path(__file__).resolve().parents[1]
# The dataset is generated (mock_banksync/data/generate.py) and anchored to its
# --as-of date, so test windows and row counts are derived from it rather than
# hardcoding a point in time.
ALL = sorted(TRANSACTIONS, key=lambda row: (row["date"], row["id"]))
DATES = {"from": ALL[0]["date"], "to": ALL[-1]["date"]}


@pytest.fixture
def client():
    with TestClient(app, headers={"X-API-Key": MOCK_KEY}) as client:
        yield client


def test_all_pages_match_saved_data_and_schema(client):
    schema = json.loads((ROOT / "tests/transaction-response.schema.json").read_text())
    # BankSync's published spec uses nullable, an OpenAPI 3.0 convention.
    # Convert it to a JSON Schema union so validation also checks null correctly.
    def normalize(node):
        if isinstance(node, dict):
            if node.pop("nullable", False) and isinstance(node.get("type"), str):
                node["type"] = [node["type"], "null"]
            for value in node.values():
                normalize(value)
        elif isinstance(node, list):
            for value in node:
                normalize(value)
    normalize(schema)
    validator = Draft202012Validator(schema)
    params = copy.copy(DATES)
    rows = []
    pages = 0
    remaining = len(TRANSACTIONS)
    while True:
        response = client.get(PATH, params=params)
        assert response.status_code == 200
        body = response.json()
        validator.validate(body)
        page_size = min(25, remaining)
        assert body["meta"]["count"] == len(body["data"]) == page_size
        remaining -= page_size
        rows.extend(body["data"])
        pages += 1
        if not body["meta"]["hasMore"]:
            assert body["meta"]["cursor"] is None
            break
        params["cursor"] = body["meta"]["cursor"]
    assert pages == (len(TRANSACTIONS) + 24) // 25
    assert rows == ALL
    assert len({row["id"] for row in rows}) == len(TRANSACTIONS)
    assert client.get(PATH, params=DATES).json() == client.get(PATH, params=DATES).json()


def test_date_boundaries_and_empty_period(client):
    single = ALL[0]["date"]
    body = client.get(PATH, params={"from": single, "to": single}).json()
    assert body["data"] and all(row["date"] == single for row in body["data"])
    body = client.get(PATH, params={"from": "2020-01-01", "to": "2020-01-31"}).json()
    assert body["data"] == []
    assert body["meta"]["count"] == 0
    assert body["meta"]["hasMore"] is False


@pytest.mark.parametrize("dates", [
    {"from": "2026-02-30"}, {"from": "20260701"},
    {"from": "2026-09-30", "to": "2026-07-01"}, {"to": ""},
])
def test_invalid_dates(client, dates):
    response = client.get(PATH, params=dates)
    assert response.status_code == 400
    assert response.json()["success"] is False


def test_auth_and_unknown_account(client):
    for headers in ({}, {"X-API-Key": "wrong"}):
        with TestClient(app) as anonymous:
            response = anonymous.get(PATH, headers=headers)
            assert response.status_code == 401
            assert response.json()["success"] is False
    assert client.get(PATH.replace("spending", "unknown")).status_code == 404


def test_cursor_rejects_invalid_token_and_changed_dates(client):
    for token in ("bad cursor", ""):
        assert client.get(PATH, params={**DATES, "cursor": token}).status_code == 400
    cursor = client.get(PATH, params=DATES).json()["meta"]["cursor"]
    next_day = (date.fromisoformat(DATES["from"]) + timedelta(days=1)).isoformat()
    response = client.get(PATH, params={**DATES, "from": next_day, "cursor": cursor})
    assert response.status_code == 400


def test_client_reads_all_pages(client):
    adapter = BankSyncClient()
    adapter.close()
    adapter.http = client
    rows = adapter.transactions(DATES["from"], DATES["to"])
    assert len(rows) == len(TRANSACTIONS)


def test_client_surfaces_auth_failure():
    adapter = BankSyncClient()
    adapter.close()
    with TestClient(app) as anonymous:
        adapter.http = anonymous
        with pytest.raises(httpx.HTTPStatusError):
            adapter.transactions(DATES["from"], DATES["to"])


def test_fixture_amounts():
    from decimal import Decimal
    assert any(row["pending"] for row in TRANSACTIONS)
    assert any("merchantName" not in row for row in TRANSACTIONS)
    for row in TRANSACTIONS:
        assert Decimal(str(row["amount"])) == (
            Decimal(str(row["creditAmount"])) - Decimal(str(row["debitAmount"]))
        )
