import os
from unittest.mock import MagicMock, patch
from fastapi.testclient import TestClient
from auditor.main import app

client = TestClient(app)

def test_audit_endpoint_fails_when_unconfigured(monkeypatch):
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    monkeypatch.delenv("GOOGLE_API_KEY", raising=False)
    response = client.post("/audit", json={"goal": "SAVINGS", "payFrequency": "FORTNIGHTLY"})
    assert response.status_code == 500
    assert "not configured" in response.json()["detail"]

def test_query_endpoint_fails_when_unconfigured(monkeypatch):
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    monkeypatch.delenv("GOOGLE_API_KEY", raising=False)
    response = client.post("/query", json={"query": "What is my total spend on Uber Eats?"})
    assert response.status_code == 500
    assert "not configured" in response.json()["detail"]

@patch("auditor.main.get_genai_client")
def test_audit_endpoint_returns_three_chips(mock_get_client):
    mock_client = MagicMock()
    mock_response = MagicMock()
    mock_response.text = """[
        {"text":"Nice work on your cafe spending this week. You have room for a cheeky brunch!","recency":"this_week","isSerendipity":false},
        {"text":"You have $35 ready if you want Friday night pizza.","recency":"this_pay_cycle","isSerendipity":false},
        {"text":"Your utility bills came in $140 lower this quarter. Want to see why?","recency":"this_quarter","isSerendipity":true}
    ]"""
    mock_client.models.generate_content.return_value = mock_response
    mock_get_client.return_value = mock_client

    response = client.post("/audit", json={"goal": "SAVINGS", "payFrequency": "FORTNIGHTLY"})
    assert response.status_code == 200
    data = response.json()
    assert "chips" in data
    chips = data["chips"]
    assert len(chips) == 3
    serendipity_count = sum(1 for c in chips if c.get("isSerendipity") is True)
    assert serendipity_count == 1

@patch("auditor.main.get_genai_client")
def test_query_endpoint_parser_spec_compute(mock_get_client):
    mock_client = MagicMock()
    mock_response = MagicMock()
    mock_response.text = """{
        "target_metric": "TOTAL_SUM",
        "included_merchants": ["Uber Eats"],
        "excluded_merchants": [],
        "included_categories": [],
        "excluded_categories": [],
        "temporal_grain": "MONTH",
        "denominator_strategy": "ACTIVE_PERIODS_ONLY",
        "exclude_pending": true,
        "exclude_internal_transfers": true
    }"""
    mock_client.models.generate_content.return_value = mock_response
    mock_get_client.return_value = mock_client

    response = client.post("/query", json={"query": "What is my total spend on Uber Eats?"})
    assert response.status_code == 200
    data = response.json()
    assert "summary" in data
    assert "value" in data
    assert "spec" in data
    spec = data["spec"]
    assert spec["target_metric"] == "TOTAL_SUM"
    assert "Uber Eats" in spec["included_merchants"]

def make_compute_payload(**overrides):
    payload = {
        "target_metric": "TOTAL_SUM",
        "included_merchants": [],
        "excluded_merchants": [],
        "included_categories": [],
        "excluded_categories": [],
        "temporal_grain": "MONTH",
        "denominator_strategy": "ACTIVE_PERIODS_ONLY",
        "exclude_pending": True,
        "exclude_internal_transfers": True
    }
    payload.update(overrides)
    return payload

def test_compute_endpoint_total_sum():
    payload = make_compute_payload(included_merchants=["Uber Eats"])
    response = client.post("/compute", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert "summary" in data
    assert "value" in data
    assert data["spec"]["target_metric"] == "TOTAL_SUM"
    assert "Uber Eats" in data["spec"]["included_merchants"]

def test_compute_endpoint_monthly_average():
    payload = make_compute_payload(
        target_metric="MONTHLY_AVERAGE",
        included_categories=["groceries"]
    )
    response = client.post("/compute", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert "summary" in data
    assert "value" in data
    assert isinstance(data["value"], (int, float))
